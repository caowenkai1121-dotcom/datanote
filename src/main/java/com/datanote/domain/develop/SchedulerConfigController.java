package com.datanote.domain.develop;

import com.datanote.domain.orchestration.dto.ScheduleConfigRequest;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import org.springframework.scheduling.support.CronExpression;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调度配置 Controller — 脚本和同步任务的调度参数管理
 */
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
@Tag(name = "调度配置", description = "脚本和同步任务的调度配置管理")
public class SchedulerConfigController {

    private final DnScriptMapper scriptMapper;
    private final DnSyncTaskMapper syncTaskMapper;

    /**
     * 获取脚本的调度配置
     */
    @GetMapping("/config/{scriptId}")
    @Operation(summary = "获取脚本调度配置")
    public R<Map<String, Object>> getConfig(@PathVariable Long scriptId) {
        DnScript script = requireScript(scriptId);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scheduleCron", script.getScheduleCron());
        config.put("scheduleStatus", script.getScheduleStatus());
        config.put("timeoutSeconds", script.getTimeoutSeconds());
        config.put("retryTimes", script.getRetryTimes());
        config.put("retryInterval", script.getRetryInterval());
        config.put("warningType", script.getWarningType());
        config.put("alertChannel", script.getAlertChannel());
        config.put("alertContact", script.getAlertContact());
        config.put("dsWorkflowCode", script.getDsWorkflowCode());
        config.put("dsScheduleId", script.getDsScheduleId());
        return R.ok(config);
    }

    /**
     * 保存调度配置（仅保存到 DataNote，不推送 DS）
     */
    @PostMapping("/config/{scriptId}")
    @Operation(summary = "保存脚本调度配置")
    public R<Void> saveConfig(@PathVariable Long scriptId, @RequestBody ScheduleConfigRequest req) {
        requireScript(scriptId);
        validateScheduleConfig(req);
        DnScript update = new DnScript();
        update.setId(scriptId);
        update.setScheduleCron(req.getScheduleCron());
        update.setTimeoutSeconds(req.getTimeoutSeconds());
        update.setRetryTimes(req.getRetryTimes());
        update.setRetryInterval(req.getRetryInterval());
        update.setWarningType(req.getWarningType());
        update.setAlertChannel(req.getAlertChannel());
        update.setAlertContact(req.getAlertContact());
        scriptMapper.updateById(update);
        return R.ok();
    }

    /**
     * 获取同步任务的调度配置
     */
    @GetMapping("/sync-config/{taskId}")
    @Operation(summary = "获取同步任务调度配置")
    public R<Map<String, Object>> getSyncConfig(@PathVariable Long taskId) {
        DnSyncTask task = requireSyncTask(taskId);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scheduleCron", task.getScheduleCron());
        config.put("scheduleStatus", task.getScheduleStatus());
        config.put("timeoutSeconds", task.getTimeoutSeconds());
        config.put("retryTimes", task.getRetryTimes());
        config.put("retryInterval", task.getRetryInterval());
        config.put("warningType", task.getWarningType());
        config.put("alertChannel", task.getAlertChannel());
        config.put("alertContact", task.getAlertContact());
        config.put("dsWorkflowCode", task.getDsWorkflowCode());
        config.put("dsScheduleId", task.getDsScheduleId());
        return R.ok(config);
    }

    /**
     * 保存同步任务调度配置
     */
    @PostMapping("/sync-config/{taskId}")
    @Operation(summary = "保存同步任务调度配置")
    public R<Void> saveSyncConfig(@PathVariable Long taskId, @RequestBody ScheduleConfigRequest req) {
        requireSyncTask(taskId);
        validateScheduleConfig(req);
        DnSyncTask update = new DnSyncTask();
        update.setId(taskId);
        update.setScheduleCron(req.getScheduleCron());
        update.setTimeoutSeconds(req.getTimeoutSeconds());
        update.setRetryTimes(req.getRetryTimes());
        update.setRetryInterval(req.getRetryInterval());
        update.setWarningType(req.getWarningType());
        update.setAlertChannel(req.getAlertChannel());
        update.setAlertContact(req.getAlertContact());
        syncTaskMapper.updateById(update);
        return R.ok();
    }

    // ========== 内部辅助方法 ==========

    /** 调度参数校验: 非法 cron / 负超时 / 负重试 落库前拦截, 避免静默吞掉导致按错峰执行。 */
    private void validateScheduleConfig(ScheduleConfigRequest req) {
        String cron = req.getScheduleCron();
        if (cron != null && !cron.trim().isEmpty()) {
            try {
                CronExpression.parse(cron.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("非法 cron 表达式: " + cron);
            }
        }
        if (req.getTimeoutSeconds() != null && req.getTimeoutSeconds() < 0) {
            throw new BusinessException("超时时间(timeoutSeconds)不能为负");
        }
        if (req.getRetryTimes() != null && req.getRetryTimes() < 0) {
            throw new BusinessException("重试次数(retryTimes)不能为负");
        }
        if (req.getRetryInterval() != null && req.getRetryInterval() < 0) {
            throw new BusinessException("重试间隔(retryInterval)不能为负");
        }
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
}
