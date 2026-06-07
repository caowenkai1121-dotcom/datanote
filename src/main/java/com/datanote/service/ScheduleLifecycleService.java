package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.exception.BusinessException;
import com.datanote.exception.ResourceNotFoundException;
import com.datanote.mapper.DnScriptMapper;
import com.datanote.mapper.DnScriptVersionMapper;
import com.datanote.mapper.DnSyncTaskMapper;
import com.datanote.model.DnScript;
import com.datanote.model.DnScriptVersion;
import com.datanote.model.DnSyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 调度生命周期服务 —— 统一脚本(DnScript)与同步任务(DnSyncTask)的上下线(本地/远程)编排。
 *
 * 重构目标：消除原 LocalSchedulerController 中脚本与同步两类逐字重复的上下线代码
 * (DS 结果回写 90-97/204-211、置 ONLINE/OFFLINE 四处、本地上线+依赖刷新两处)。
 * 行为与重构前一字不变：参数校验、DS 字段回写、版本快照、依赖刷新、状态位均保持。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleLifecycleService {

    private final DolphinService dolphinService;
    private final DnScriptMapper scriptMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final DnScriptVersionMapper scriptVersionMapper;
    private final TaskDependencyService taskDependencyService;

    @Value("${datax.home}")
    private String dataxHome;

    @Value("${datax.job-dir}")
    private String jobDir;

    /** DS 远程上线（脚本/同步统一），返回 dsResult 供前端。失败抛出由调用方按类型映射文案。 */
    public Map<String, Object> onlineRemote(Long id, ScheduleTargetType type) throws Exception {
        if (type == ScheduleTargetType.SCRIPT) {
            DnScript script = requireScript(id);
            requireNotEmpty(script.getContent(), "脚本内容为空，无法上线");
            requireNotEmpty(script.getScheduleCron(), "请先配置调度 cron 表达式");
            Map<String, Object> dsResult = dolphinService.onlineScript(
                    script.getScriptName(),
                    script.getScriptType(),
                    script.getContent(),
                    script.getDsWorkflowCode(),
                    script.getDsTaskCode(),
                    script.getDsScheduleId(),
                    script.getScheduleCron(),
                    defaultInt(script.getTimeoutSeconds(), Constants.DEFAULT_TIMEOUT_SECONDS),
                    defaultInt(script.getRetryTimes(), Constants.DEFAULT_RETRY_TIMES),
                    defaultInt(script.getRetryInterval(), Constants.DEFAULT_RETRY_INTERVAL),
                    script.getWarningType()
            );
            applyDsResultScript(id, dsResult);
            return dsResult;
        } else {
            DnSyncTask task = requireSyncTask(id);
            requireNotEmpty(task.getScheduleCron(), "请先配置调度 cron 表达式");
            String shellScript = buildSyncShellScript(task);
            Map<String, Object> dsResult = dolphinService.onlineScript(
                    task.getTaskName(),
                    Constants.SCRIPT_TYPE_SHELL,
                    shellScript,
                    task.getDsWorkflowCode(),
                    task.getDsTaskCode(),
                    task.getDsScheduleId(),
                    task.getScheduleCron(),
                    defaultInt(task.getTimeoutSeconds(), Constants.DEFAULT_TIMEOUT_SECONDS),
                    defaultInt(task.getRetryTimes(), Constants.DEFAULT_RETRY_TIMES),
                    defaultInt(task.getRetryInterval(), Constants.DEFAULT_RETRY_INTERVAL),
                    task.getWarningType()
            );
            applyDsResultSync(id, dsResult);
            return dsResult;
        }
    }

    /** DS 远程下线（脚本/同步统一）。未上线过抛 BusinessException。 */
    public void offlineRemote(Long id, ScheduleTargetType type) throws Exception {
        if (type == ScheduleTargetType.SCRIPT) {
            DnScript script = requireScript(id);
            if (script.getDsWorkflowCode() == null || script.getDsWorkflowCode() == 0) {
                throw new BusinessException("该脚本尚未上线过");
            }
            dolphinService.offlineScript(script.getDsWorkflowCode(), script.getDsScheduleId());
            setScriptStatus(id, Constants.SCHEDULE_OFFLINE);
        } else {
            DnSyncTask task = requireSyncTask(id);
            if (task.getDsWorkflowCode() == null || task.getDsWorkflowCode() == 0) {
                throw new BusinessException("该任务尚未上线过");
            }
            dolphinService.offlineScript(task.getDsWorkflowCode(), task.getDsScheduleId());
            setSyncStatus(id, Constants.SCHEDULE_OFFLINE);
        }
    }

    /** 本地上线（脚本含版本快照），统一置 ONLINE 并刷新依赖。 */
    public void onlineLocal(Long id, ScheduleTargetType type) {
        if (type == ScheduleTargetType.SCRIPT) {
            DnScript script = requireScript(id);
            requireNotEmpty(script.getContent(), "脚本内容为空，无法上线");
            createOnlineVersion(script);
            setScriptStatus(id, Constants.SCHEDULE_ONLINE);
        } else {
            requireSyncTask(id);
            setSyncStatus(id, Constants.SCHEDULE_ONLINE);
        }
        taskDependencyService.refreshAllDependencies();
    }

    /** 本地下线（脚本/同步统一）：仅置 OFFLINE，不刷新依赖（与重构前一致）。 */
    public void offlineLocal(Long id, ScheduleTargetType type) {
        if (type == ScheduleTargetType.SCRIPT) {
            setScriptStatus(id, Constants.SCHEDULE_OFFLINE);
        } else {
            setSyncStatus(id, Constants.SCHEDULE_OFFLINE);
        }
    }

    // ========== 内部辅助（消除重复） ==========

    /** 统一回写 DS 结果（dsProjectCode/WorkflowCode/TaskCode/ScheduleId）+ 置 ONLINE —— 脚本。 */
    private void applyDsResultScript(Long id, Map<String, Object> dsResult) {
        DnScript update = new DnScript();
        update.setId(id);
        update.setDsProjectCode((Long) dsResult.get("dsProjectCode"));
        update.setDsWorkflowCode((Long) dsResult.get("dsWorkflowCode"));
        update.setDsTaskCode((Long) dsResult.get("dsTaskCode"));
        update.setDsScheduleId((Integer) dsResult.get("dsScheduleId"));
        update.setScheduleStatus(Constants.SCHEDULE_ONLINE);
        scriptMapper.updateById(update);
    }

    /** 统一回写 DS 结果 + 置 ONLINE —— 同步任务。 */
    private void applyDsResultSync(Long id, Map<String, Object> dsResult) {
        DnSyncTask update = new DnSyncTask();
        update.setId(id);
        update.setDsProjectCode((Long) dsResult.get("dsProjectCode"));
        update.setDsWorkflowCode((Long) dsResult.get("dsWorkflowCode"));
        update.setDsTaskCode((Long) dsResult.get("dsTaskCode"));
        update.setDsScheduleId((Integer) dsResult.get("dsScheduleId"));
        update.setScheduleStatus(Constants.SCHEDULE_ONLINE);
        syncTaskMapper.updateById(update);
    }

    private void setScriptStatus(Long id, String status) {
        DnScript update = new DnScript();
        update.setId(id);
        update.setScheduleStatus(status);
        scriptMapper.updateById(update);
    }

    private void setSyncStatus(Long id, String status) {
        DnSyncTask update = new DnSyncTask();
        update.setId(id);
        update.setScheduleStatus(status);
        syncTaskMapper.updateById(update);
    }

    /** 同步任务 DataX shell 脚本生成（自 LocalSchedulerController.syncOnline 整段迁入，逐字不变）。 */
    private String buildSyncShellScript(DnSyncTask task) {
        String jobFile = jobDir + "/" + task.getTargetTable() + ".json";
        return "#!/bin/bash\n"
                + "# DataNote 同步任务: " + task.getTaskName() + "\n"
                + "DT=${1:-$(date -d '-1 day' +%Y-%m-%d)}\n"
                + "echo \"同步任务: " + task.getSourceDb() + "." + task.getSourceTable()
                + " -> ods." + task.getTargetTable() + ", dt=$DT\"\n"
                + "java -server -Xms1g -Xmx1g"
                + " -Ddatax.home=" + dataxHome
                + " -classpath " + dataxHome + "/lib/*"
                + " com.alibaba.datax.core.Engine"
                + " -mode standalone -jobid -1"
                + " -job " + jobFile + "\n";
    }

    /** 上线时创建版本快照，commitMsg 含"上线"以区分（自 Controller 迁入，逐字不变）。 */
    private void createOnlineVersion(DnScript script) {
        String content = script.getContent();
        if (content == null || content.trim().isEmpty()) return;

        QueryWrapper<DnScriptVersion> qw = new QueryWrapper<>();
        qw.eq("script_id", script.getId()).orderByDesc("version").last("LIMIT 1");
        DnScriptVersion latest = scriptVersionMapper.selectOne(qw);

        DnScriptVersion ver = new DnScriptVersion();
        ver.setScriptId(script.getId());
        ver.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        ver.setContent(content);
        ver.setCommitMsg("上线版本快照");
        ver.setCommittedBy("system");
        ver.setCommittedAt(LocalDateTime.now());
        ver.setVersionType("online");
        scriptVersionMapper.insert(ver);
    }

    private DnScript requireScript(Long scriptId) {
        DnScript script = scriptMapper.selectById(scriptId);
        if (script == null) {
            throw new ResourceNotFoundException("脚本");
        }
        return script;
    }

    private DnSyncTask requireSyncTask(Long taskId) {
        DnSyncTask task = syncTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("同步任务");
        }
        return task;
    }

    private void requireNotEmpty(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(message);
        }
    }

    private int defaultInt(Integer value, int defaultVal) {
        return value != null ? value : defaultVal;
    }
}
