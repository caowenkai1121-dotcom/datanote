package com.datanote.domain.orchestration;

import com.alibaba.fastjson.JSONArray;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 调度监控查询门面 —— 今日调度状态、本地运行日志、DS 调度日志、运行记录。
 * 由原 LocalSchedulerController 拆出，路由前缀与路径一字不变，逻辑逐字迁移。
 */
@Slf4j
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
@Tag(name = "调度监控查询", description = "今日状态、运行记录、本地与 DS 执行日志")
public class ScheduleMonitorController {

    private final TaskSchedulerService taskSchedulerService;
    private final DolphinService dolphinService;
    private final DnScriptMapper scriptMapper;
    private final com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper schedulerRunMapper;   // 全站#6 补数记录

    @GetMapping("/today")
    @Operation(summary = "获取今日调度状态")
    public R<Map<String, Object>> getTodayStatus(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Boolean myTask) {
        LocalDate runDate = date != null ? date : LocalDate.now().minusDays(1);
        // 勾选"我的任务"时按当前登录用户过滤(原 myTask 参数后端未消费, 过滤语义失效)
        String createdBy = (myTask != null && myTask) ? com.datanote.platform.iam.CurrentUserUtil.currentUser() : null;
        return R.ok(taskSchedulerService.getTodayStatus(runDate, createdBy));
    }

    @GetMapping("/backfill-runs")
    @Operation(summary = "补数运行记录(全站#6: 补数链路收敛 scheduler, 查 run_type=backfill)")
    public R<List<com.datanote.domain.orchestration.model.DnSchedulerRun>> backfillRuns(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String taskType,
            @RequestParam(defaultValue = "200") int limit) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.datanote.domain.orchestration.model.DnSchedulerRun> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        qw.eq(com.datanote.domain.orchestration.model.DnSchedulerRun::getRunType, "backfill");
        if (date != null) qw.eq(com.datanote.domain.orchestration.model.DnSchedulerRun::getRunDate, date);
        if (batchId != null && !batchId.trim().isEmpty()) qw.eq(com.datanote.domain.orchestration.model.DnSchedulerRun::getBatchId, batchId.trim());
        if (taskId != null) qw.eq(com.datanote.domain.orchestration.model.DnSchedulerRun::getTaskId, taskId);
        if (taskType != null && !taskType.trim().isEmpty()) qw.eq(com.datanote.domain.orchestration.model.DnSchedulerRun::getTaskType, taskType.trim());
        qw.orderByDesc(com.datanote.domain.orchestration.model.DnSchedulerRun::getId).last("LIMIT " + Math.min(Math.max(limit, 1), 1000));
        return R.ok(schedulerRunMapper.selectList(qw));
    }

    @GetMapping("/run-log/{runId}")
    @Operation(summary = "获取本地任务执行日志")
    public R<String> getRunLog(@PathVariable Long runId) {
        String logContent = taskSchedulerService.getRunLog(runId);
        return R.ok(logContent != null ? logContent : "");
    }

    @GetMapping("/logs/{scriptId}")
    @Operation(summary = "查询DS调度执行日志")
    public R<JSONArray> getLogs(@PathVariable Long scriptId,
                               @RequestParam(defaultValue = "1") int pageNo,
                               @RequestParam(defaultValue = "20") int pageSize) {
        try {
            DnScript script = requireScript(scriptId);
            if (script.getDsWorkflowCode() == null || script.getDsWorkflowCode() == 0) {
                return R.ok(new JSONArray());
            }
            JSONArray instances = dolphinService.getTaskInstances(script.getScriptName(), pageNo, pageSize);
            return R.ok(instances != null ? instances : new JSONArray());
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询调度执行日志失败, scriptId={}", scriptId, e);
            return R.fail("查询日志失败");
        }
    }

    @GetMapping("/log-detail/{taskInstanceId}")
    @Operation(summary = "读取DS调度执行详细日志")
    public R<String> getLogDetail(@PathVariable int taskInstanceId,
                                  @RequestParam(defaultValue = "0") int skipLineNum,
                                  @RequestParam(defaultValue = "1000") int limit) {
        try {
            return R.ok(dolphinService.getTaskLog(taskInstanceId, skipLineNum, limit));
        } catch (Exception e) {
            log.error("读取调度执行日志详情失败, taskInstanceId={}", taskInstanceId, e);
            return R.fail("读取日志详情失败");
        }
    }

    @GetMapping("/task-runs")
    @Operation(summary = "查询任务本地运行记录")
    public R<List<Map<String, Object>>> getTaskRuns(
            @RequestParam Long taskId, @RequestParam String taskType,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(taskSchedulerService.getTaskRuns(taskId, taskType, limit));
    }

    private DnScript requireScript(Long scriptId) {
        DnScript script = scriptMapper.selectById(scriptId);
        if (script == null) {
            throw new ResourceNotFoundException("脚本");
        }
        return script;
    }
}
