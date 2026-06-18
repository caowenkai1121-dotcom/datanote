package com.datanote.domain.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.Constants;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.mapper.DnScriptVersionMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.develop.model.DnScriptVersion;
import com.datanote.domain.integration.model.DnSyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        requireId(id);
        requireType(type);
        if (type == ScheduleTargetType.SCRIPT) {
            rejectDirectScriptOnline();
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
        requireId(id);
        requireType(type);
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

    /** 本地上线（脚本含版本快照），统一置 ONLINE 并刷新依赖。
     *  R47审查修复: 不加 @Transactional —— refreshAllDependencies() 跨 bean 默认 REQUIRED 会并入外层事务,
     *  循环依赖时它抛异常将连带回滚"版本快照+置 ONLINE", 改变状态机语义。各步保持独立提交(与重构前一致)。 */
    public void onlineLocal(Long id, ScheduleTargetType type) {
        requireId(id);
        requireType(type);
        if (type == ScheduleTargetType.SCRIPT) {
            rejectDirectScriptOnline();
        }
        onlineLocalInternal(id, type);
    }

    public void onlineLocalAfterApproval(Long id, ScheduleTargetType type) {
        requireId(id);
        requireType(type);
        if (type != ScheduleTargetType.SCRIPT) {
            throw new BusinessException("仅脚本上线审批可调用审批后上线入口");
        }
        onlineLocalInternal(id, type);
    }

    private void onlineLocalInternal(Long id, ScheduleTargetType type) {
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

    private void rejectDirectScriptOnline() {
        throw new BusinessException("脚本上线请先提交并通过上线审批");
    }

    /** 本地下线（脚本/同步统一）：仅置 OFFLINE，不刷新依赖（与重构前一致）。 */
    public void offlineLocal(Long id, ScheduleTargetType type) {
        requireId(id);
        requireType(type);
        if (type == ScheduleTargetType.SCRIPT) {
            setScriptStatus(id, Constants.SCHEDULE_OFFLINE);
        } else {
            setSyncStatus(id, Constants.SCHEDULE_OFFLINE);
        }
    }

    // ========== 内部辅助（消除重复） ==========

    /** 统一回写 DS 结果（dsProjectCode/WorkflowCode/TaskCode/ScheduleId）+ 置 ONLINE —— 脚本。 */
    private void applyDsResultScript(Long id, Map<String, Object> dsResult) {
        requireDsResult(dsResult);
        DnScript update = new DnScript();
        update.setId(id);
        update.setDsProjectCode(asLong(dsResult.get("dsProjectCode")));
        update.setDsWorkflowCode(asLong(dsResult.get("dsWorkflowCode")));
        update.setDsTaskCode(asLong(dsResult.get("dsTaskCode")));
        update.setDsScheduleId(asInteger(dsResult.get("dsScheduleId")));
        update.setScheduleStatus(Constants.SCHEDULE_ONLINE);
        scriptMapper.updateById(update);
    }

    /** 统一回写 DS 结果 + 置 ONLINE —— 同步任务。 */
    private void applyDsResultSync(Long id, Map<String, Object> dsResult) {
        requireDsResult(dsResult);
        DnSyncTask update = new DnSyncTask();
        update.setId(id);
        update.setDsProjectCode(asLong(dsResult.get("dsProjectCode")));
        update.setDsWorkflowCode(asLong(dsResult.get("dsWorkflowCode")));
        update.setDsTaskCode(asLong(dsResult.get("dsTaskCode")));
        update.setDsScheduleId(asInteger(dsResult.get("dsScheduleId")));
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
        requireNotEmpty(task.getTargetTable(), "同步任务未配置目标表(targetTable)，无法生成 DataX 作业");
        String targetTable = requireSafeShellToken(task.getTargetTable(), "targetTable");
        String sourceDb = requireSafeShellToken(task.getSourceDb(), "sourceDb");
        String sourceTable = requireSafeShellToken(task.getSourceTable(), "sourceTable");
        String jobFile = jobDir + "/" + targetTable + ".json";
        return "#!/bin/bash\n"
                + "# DataNote 同步任务: " + oneLine(task.getTaskName()) + "\n"
                + "DT=${1:-$(date -d '-1 day' +%Y-%m-%d)}\n"
                + "echo " + shellQuote("同步任务: " + sourceDb + "." + sourceTable
                + " -> ods." + targetTable + ", dt=") + "\"$DT\"\n"
                + "java -server -Xms1g -Xmx1g"
                + " -Ddatax.home=" + shellQuote(dataxHome)
                + " -classpath " + shellQuote(dataxHome + "/lib/*")
                + " com.alibaba.datax.core.Engine"
                + " -mode standalone -jobid -1"
                + " -job " + shellQuote(jobFile) + "\n";
    }

    private String requireSafeShellToken(String value, String field) {
        requireNotEmpty(value, field + " 不能为空");
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new BusinessException(field + " 含非法字符");
        }
        return value;
    }

    private String shellQuote(String value) {
        return "'" + String.valueOf(value == null ? "" : value).replace("'", "'\"'\"'") + "'";
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
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

    private void requireId(Long id) {
        if (id == null) {
            throw new BusinessException("调度目标 id 不能为空");
        }
    }

    private void requireType(ScheduleTargetType type) {
        if (type == null) {
            throw new BusinessException("调度目标类型不能为空");
        }
    }

    /** DS 上线返回结果不可为空，否则无法回写编排信息（视为远程上线异常）。 */
    private void requireDsResult(Map<String, Object> dsResult) {
        if (dsResult == null) {
            throw new BusinessException("DS 远程上线未返回结果，无法回写调度信息");
        }
    }

    /** 安全提取 Long：兼容 DS 返回的 Long/Integer 数字类型，缺失或 null 返回 null（保持原回写语义）。 */
    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        throw new BusinessException("DS 返回的调度编码类型非法: " + value.getClass().getSimpleName());
    }

    /** 安全提取 Integer：兼容 Number 子类型，缺失或 null 返回 null（保持原回写语义）。 */
    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        throw new BusinessException("DS 返回的调度 id 类型非法: " + value.getClass().getSimpleName());
    }

    private int defaultInt(Integer value, int defaultVal) {
        return value != null ? value : defaultVal;
    }
}
