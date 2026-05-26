package com.datanote.sync.service;

import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.FullSyncEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 全量同步执行入口：被手动触发或调度调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobExecutor {

    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final LogBroadcastService logBroadcastService;

    private static final String TASK_TYPE = "DbSync";

    /** 执行一个同步任务（当前仅 FULL）。返回执行记录 id。 */
    public Long run(Long jobId, String triggerType) {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("同步任务不存在: " + jobId);
        }

        DnTaskExecution exec = new DnTaskExecution();
        exec.setSyncTaskId(jobId);
        exec.setTaskType(TASK_TYPE);
        exec.setTriggerType(triggerType);
        exec.setStatus("RUNNING");
        exec.setStartTime(LocalDateTime.now());
        exec.setReadCount(0L);
        exec.setWriteCount(0L);
        exec.setErrorCount(0L);
        exec.setCreatedAt(LocalDateTime.now());
        taskExecutionMapper.insert(exec);

        StringBuilder logBuf = new StringBuilder();
        SyncContext ctx = new SyncContext();
        ctx.setJobId(jobId);
        ctx.setExecutionId(exec.getId());
        ctx.setSourceDb(job.getSourceDb());
        ctx.setTargetDb(job.getTargetDb());
        ctx.setWriteMode(job.getWriteMode() == null ? "UPSERT" : job.getWriteMode());
        ctx.setBatchSize(job.getBatchSize() == null ? 1000 : job.getBatchSize());
        List<TableSyncConfig> tables = syncJobService.parseTables(job);
        ctx.setTables(tables);
        ctx.setSource(syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb()));
        ctx.setTarget(syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb()));
        ctx.setLogCallback((level, msg) -> {
            logBuf.append("[").append(level).append("] ").append(msg).append("\n");
            logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, level, msg);
        });

        String finalStatus;
        try {
            new FullSyncEngine().sync(ctx);
            finalStatus = ctx.getErrorCount().get() > 0 ? "FAILED" : "SUCCESS";
        } catch (Exception e) {
            log.error("同步任务执行失败: jobId={}", jobId, e);
            ctx.log("ERROR", "执行失败: " + e.getMessage());
            finalStatus = "FAILED";
        }

        LocalDateTime end = LocalDateTime.now();
        exec.setStatus(finalStatus);
        exec.setEndTime(end);
        exec.setDuration((int) Duration.between(exec.getStartTime(), end).getSeconds());
        exec.setReadCount(ctx.getReadCount().get());
        exec.setWriteCount(ctx.getWriteCount().get());
        exec.setErrorCount(ctx.getErrorCount().get());
        String logStr = logBuf.toString();
        exec.setLog(logStr.length() > 1_000_000 ? logStr.substring(logStr.length() - 1_000_000) : logStr);
        taskExecutionMapper.updateById(exec);

        logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, "INFO",
                "任务结束: " + finalStatus + "，读 " + ctx.getReadCount().get()
                + " 写 " + ctx.getWriteCount().get());
        return exec.getId();
    }
}
