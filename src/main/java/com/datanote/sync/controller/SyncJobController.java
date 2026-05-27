package com.datanote.sync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
        return R.ok(syncJobService.save(job));
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

    @Operation(summary = "移动任务到文件夹")
    @PostMapping("/{id}/move")
    public R<String> move(@PathVariable Long id, @RequestParam Long folderId) {
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
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
}
