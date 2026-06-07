package com.datanote.domain.orchestration;

import com.datanote.common.Constants;
import com.datanote.dto.BackfillRequest;
import com.datanote.dto.PauseDownstreamRequest;
import com.datanote.model.R;
import com.datanote.model.dto.CronPreviewRequest;
import com.datanote.domain.orchestration.TaskDependencyService;
import com.datanote.domain.orchestration.TaskExecutionService;
import com.datanote.domain.orchestration.TaskSchedulerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度执行控制门面 —— 引擎控制 + 执行控制（启动每日/停止/重试/重跑/停任务/暂停下游/恢复/补数/批量重跑/Cron预览）。
 * 由原 LocalSchedulerController 拆出，路由前缀与路径一字不变，逻辑逐字迁移。
 */
@Slf4j
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
@Tag(name = "调度执行控制", description = "引擎启停、重试/重跑、补数据、暂停恢复、Cron预览")
public class ScheduleExecutionController {

    private final TaskSchedulerService taskSchedulerService;
    private final TaskExecutionService taskExecutionService;
    private final TaskDependencyService taskDependencyService;

    @PostMapping("/start-daily")
    @Operation(summary = "启动每日调度")
    public R<Map<String, Object>> startDailyRun(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate runDate = date != null ? date : LocalDate.now().minusDays(1);
        return R.ok(taskSchedulerService.startDailyRun(runDate));
    }

    @PostMapping("/stop")
    @Operation(summary = "停止调度引擎")
    public R<Void> stopScheduler() {
        taskSchedulerService.setEnabled(false);
        return R.ok();
    }

    @PostMapping("/retry/{runId}")
    @Operation(summary = "重试失败的任务")
    public R<Void> retryTask(@PathVariable Long runId) {
        taskExecutionService.retryTask(runId);
        return R.ok();
    }

    @PostMapping("/rerun/{runId}")
    @Operation(summary = "重新调度")
    public R<Void> rerunTask(@PathVariable Long runId) {
        taskExecutionService.retryTask(runId);
        return R.ok();
    }

    @PostMapping("/stop-task/{runId}")
    @Operation(summary = "停止运行中的任务")
    public R<Void> stopTask(@PathVariable Long runId) {
        taskSchedulerService.stopTask(runId);
        return R.ok();
    }

    @PostMapping("/pause-downstream")
    @Operation(summary = "暂停指定任务的所有下游")
    public R<Map<String, Object>> pauseDownstreamByTask(@RequestBody PauseDownstreamRequest req) {
        LocalDate runDate = req.getRunDate() != null ? req.getRunDate() : LocalDate.now().minusDays(1);
        String runType = req.getRunType() != null ? req.getRunType() : Constants.RUN_TYPE_DAILY;

        int paused = taskDependencyService.pauseDownstream(req.getTaskId(), req.getTaskType(), runDate, runType);
        Map<String, Object> result = new HashMap<>();
        result.put("pausedCount", paused);
        return R.ok(result);
    }

    @PostMapping("/resume")
    @Operation(summary = "恢复所有暂停的任务")
    public R<Map<String, Object>> resumePaused(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate runDate = date != null ? date : LocalDate.now().minusDays(1);
        int resumed = taskSchedulerService.resumePaused(runDate, Constants.RUN_TYPE_DAILY);
        Map<String, Object> result = new HashMap<>();
        result.put("resumedCount", resumed);
        return R.ok(result);
    }

    @PostMapping("/backfill")
    @Operation(summary = "补数据")
    public R<Map<String, Object>> startBackfill(@RequestBody BackfillRequest req) {
        return R.ok(taskSchedulerService.startBackfill(
                req.getTaskId(), req.getTaskType(), req.getRunDate(), req.getSelectedTasks()));
    }

    @PostMapping("/batch-retry")
    @Operation(summary = "批量重跑任务")
    public R<Map<String, Object>> batchRetry(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> runIds = (List<Integer>) body.get("runIds");
        String date = (String) body.get("date");
        int count = taskSchedulerService.batchRetry(runIds, date);
        Map<String, Object> result = new HashMap<>();
        result.put("retried", count);
        return R.ok(result);
    }

    @PostMapping("/cron-preview")
    @Operation(summary = "Cron表达式预览")
    public R<List<String>> cronPreview(@RequestBody CronPreviewRequest req) {
        String cronExpr = req.getCron();
        int count = req.getCount() != null ? req.getCount() : 5;
        if (cronExpr == null || cronExpr.trim().isEmpty()) {
            return R.fail("Cron 表达式不能为空");
        }
        try {
            CronExpression expr = CronExpression.parse(cronExpr);
            List<String> times = new ArrayList<>();
            LocalDateTime next = LocalDateTime.now();
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (int i = 0; i < count; i++) {
                next = expr.next(next);
                if (next == null) break;
                times.add(next.format(fmt));
            }
            return R.ok(times);
        } catch (Exception e) {
            return R.fail("无效的 Cron 表达式: " + e.getMessage());
        }
    }
}
