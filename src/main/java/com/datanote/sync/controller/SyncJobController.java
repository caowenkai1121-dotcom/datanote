package com.datanote.sync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnSyncFolderMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.model.R;
import com.datanote.sync.service.SyncJobExecutor;
import com.datanote.sync.service.SyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 关系库同步任务 Controller。
 */
@Slf4j
@RestController
@RequestMapping("/api/sync-job")
@Tag(name = "关系库同步", description = "关系型数据库之间的同步任务管理与执行")
@RequiredArgsConstructor
public class SyncJobController {

    private final SyncJobService syncJobService;
    private final SyncJobExecutor syncJobExecutor;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnSyncJobMapper syncJobMapper;
    private final DnSyncFolderMapper folderMapper;
    private final com.datanote.mapper.DnSyncJobAuditMapper auditMapper;
    private final com.datanote.sync.service.DataReconciliationService reconciliationService;
    private final com.datanote.sync.service.CdcEngineManager cdcEngineManager;

    @Operation(summary = "任务列表")
    @GetMapping("/list")
    public R<List<DnSyncJob>> list() {
        return R.ok(syncJobService.list());
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public R<DnSyncJob> getById(@PathVariable Long id) {
        return R.ok(syncJobService.getById(id));
    }

    @Operation(summary = "保存任务")
    @PostMapping("/save")
    public R<DnSyncJob> save(@RequestBody DnSyncJob job) {
        try {
            return R.ok(syncJobService.save(job));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "同步前预检（连通性/源表主键/目标库/cron）")
    @PostMapping("/precheck")
    public R<java.util.Map<String, Object>> precheck(@RequestBody DnSyncJob job) {
        return R.ok(syncJobService.precheck(job));
    }

    @Operation(summary = "删除任务")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        syncJobService.delete(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "手动运行（全量）")
    @PostMapping("/{id}/run")
    public R<Long> run(@PathVariable Long id) {
        try {
            Long execId = syncJobExecutor.run(id, "manual");
            return R.ok(execId);
        } catch (Exception e) {
            log.error("运行同步任务失败: id={}", id, e);
            return R.fail("运行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "停止运行中的任务（全量/增量）")
    @PostMapping("/{id}/stop")
    public R<String> stop(@PathVariable Long id) {
        boolean hit = syncJobExecutor.stop(id);
        return hit ? R.ok("已请求停止") : R.fail("任务当前不在运行中");
    }

    @Operation(summary = "移动任务到文件夹")
    @PostMapping("/{id}/move")
    public R<String> move(@PathVariable Long id, @RequestParam Long folderId) {
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
        }
        // folderId==0 表示根目录，直接放行；否则校验文件夹是否存在
        if (folderId != null && folderId != 0) {
            if (folderMapper.selectById(folderId) == null) {
                return R.fail("目标文件夹不存在");
            }
        }
        job.setFolderId(folderId);
        job.setUpdatedAt(java.time.LocalDateTime.now());
        syncJobMapper.updateById(job);
        return R.ok("已移动");
    }

    @Operation(summary = "执行历史")
    @GetMapping("/{id}/executions")
    public R<List<DnTaskExecution>> executions(@PathVariable Long id) {
        LambdaQueryWrapper<DnTaskExecution> wrapper = new LambdaQueryWrapper<DnTaskExecution>()
                .eq(DnTaskExecution::getSyncTaskId, id)
                .eq(DnTaskExecution::getTaskType, "DbSync")
                .orderByDesc(DnTaskExecution::getId)
                .last("LIMIT 50");
        return R.ok(taskExecutionMapper.selectList(wrapper));
    }

    @Operation(summary = "操作审计历史")
    @GetMapping("/{id}/audit")
    public R<List<com.datanote.model.DnSyncJobAudit>> audit(@PathVariable Long id) {
        return R.ok(auditMapper.selectList(new LambdaQueryWrapper<com.datanote.model.DnSyncJobAudit>()
            .eq(com.datanote.model.DnSyncJobAudit::getJobId, id)
            .orderByDesc(com.datanote.model.DnSyncJobAudit::getId).last("LIMIT 100")));
    }

    @Operation(summary = "启用定时调度")
    @PostMapping("/{id}/online")
    public R<String> online(@PathVariable Long id) {
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
        }
        job.setScheduleStatus("online");
        syncJobMapper.updateById(job);
        return R.ok("已启用定时调度");
    }

    @Operation(summary = "停用定时调度")
    @PostMapping("/{id}/offline")
    public R<String> offline(@PathVariable Long id) {
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
        }
        job.setScheduleStatus("offline");
        syncJobMapper.updateById(job);
        return R.ok("已停用定时调度");
    }

    // ===== M3c：行数对账 =====

    @Operation(summary = "行数对账(源/目标 count 比对)")
    @PostMapping("/{id}/reconcile")
    public R<java.util.Map<String, Object>> reconcile(@PathVariable Long id) {
        try {
            return R.ok(reconciliationService.reconcile(id));
        } catch (Exception e) {
            log.error("对账失败: id={}", id, e);
            return R.fail("对账失败: " + e.getMessage());
        }
    }

    // ===== M3c：监控大盘（放 Controller 避免 SyncJobService 注入 CdcEngineManager 成环） =====

    @Operation(summary = "监控大盘(所有任务状态+最新计数+CDC指标)")
    @GetMapping("/dashboard")
    public R<java.util.List<java.util.Map<String, Object>>> dashboard() {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        for (DnSyncJob job : syncJobService.list()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", job.getId());
            m.put("jobName", job.getJobName());
            m.put("syncMode", job.getSyncMode());
            m.put("status", job.getStatus());
            m.put("scheduleStatus", job.getScheduleStatus());
            DnTaskExecution last = taskExecutionMapper.selectOne(new LambdaQueryWrapper<DnTaskExecution>()
                    .eq(DnTaskExecution::getSyncTaskId, job.getId())
                    .eq(DnTaskExecution::getTaskType, "DbSync")
                    .orderByDesc(DnTaskExecution::getId).last("LIMIT 1"));
            if (last != null) {
                m.put("lastStatus", last.getStatus());
                m.put("lastReadCount", last.getReadCount());
                m.put("lastWriteCount", last.getWriteCount());
                m.put("lastErrorCount", last.getErrorCount());
                m.put("lastTime", last.getStartTime());
            }
            if ("CDC".equalsIgnoreCase(job.getSyncMode())) {
                try {
                    m.putAll(prefixCdc(cdcEngineManager.metrics(job.getId())));
                } catch (Exception ignore) {
                    // CDC 指标获取失败忽略，不影响整体大盘
                }
            }
            list.add(m);
        }
        return R.ok(list);
    }

    private static java.util.Map<String, Object> prefixCdc(java.util.Map<String, Object> cdc) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (cdc != null) {
            m.put("cdcRunning", cdc.get("running"));
            m.put("cdcLagMs", cdc.get("lagMs"));
            m.put("cdcEventsSeen", cdc.get("eventsSeen"));
        }
        return m;
    }

}
