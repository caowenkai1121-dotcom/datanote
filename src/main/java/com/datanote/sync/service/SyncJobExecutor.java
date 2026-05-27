package com.datanote.sync.service;

import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.SyncEngine;
import com.datanote.sync.engine.SyncEngineFactory;
import com.datanote.sync.schema.TableSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 同步执行入口：被手动触发或调度调用。支持 FULL/INCREMENTAL，自动建表，增量断点回写。
 * <p>执行改为异步：{@link #run} 立即创建执行记录、置任务为 RUNNING 并提交线程池后返回；
 * 真正的同步体在 {@link #doRun} 中后台执行，结束时回写 dn_sync_job.status 并推送状态变更。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobExecutor {

    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final LogBroadcastService logBroadcastService;
    private final TableSchemaService tableSchemaService;

    private static final String TASK_TYPE = "DbSync";
    private static final int MAX_LOG = 1_000_000;

    /** 后台执行线程池（FULL/INCREMENTAL 一次性任务）。 */
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    /** 正在运行的 jobId 集合，防同一任务重复运行。 */
    private final Set<Long> runningJobs = ConcurrentHashMap.newKeySet();

    /**
     * 异步执行一个同步任务：校验入参、创建 RUNNING 执行记录、置任务 RUNNING 并推状态，
     * 提交线程池后立即返回执行记录 id（不阻塞）。同一 jobId 正在运行时抛异常。
     */
    public Long run(Long jobId, String triggerType) {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("同步任务不存在: " + jobId);
        }
        if (job.getSourceDsId() == null || job.getTargetDsId() == null) {
            throw new IllegalArgumentException("sourceDsId 和 targetDsId 不能为空");
        }
        if (isBlank(job.getSourceDb()) || isBlank(job.getTargetDb())) {
            throw new IllegalArgumentException("sourceDb 和 targetDb 不能为空");
        }
        if (!runningJobs.add(jobId)) {
            throw new IllegalStateException("任务正在运行中: " + jobId);
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
        try {
            taskExecutionMapper.insert(exec);
            syncJobService.updateStatus(jobId, "RUNNING");
            logBroadcastService.broadcastSyncStatus(jobId, "RUNNING");
        } catch (RuntimeException e) {
            // 入库/置状态失败则不占用 runningJobs，让用户可重试
            runningJobs.remove(jobId);
            throw e;
        }

        final Long execId = exec.getId();
        pool.submit(() -> {
            try {
                doRun(job, triggerType, execId);
            } finally {
                runningJobs.remove(jobId);
            }
        });
        return execId;
    }

    /** 真正的同步执行体（后台线程），结束时回写执行记录与任务状态、推状态变更。 */
    private void doRun(DnSyncJob job, String triggerType, Long execId) {
        Long jobId = job.getId();
        DnTaskExecution exec = taskExecutionMapper.selectById(execId);

        StringBuilder logBuf = new StringBuilder();
        String syncMode = job.getSyncMode() == null ? "FULL" : job.getSyncMode().toUpperCase();

        SyncContext ctx = new SyncContext();
        ctx.setJobId(jobId);
        ctx.setExecutionId(execId);
        ctx.setSourceDb(job.getSourceDb());
        ctx.setTargetDb(job.getTargetDb());
        ctx.setWriteMode(job.getWriteMode() == null ? "UPSERT" : job.getWriteMode());
        ctx.setBatchSize(job.getBatchSize() == null ? 1000 : job.getBatchSize());
        List<TableSyncConfig> tables = syncJobService.parseTables(job);
        ctx.setTables(tables);
        DbConnector source = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector target = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        ctx.setSource(source);
        ctx.setTarget(target);
        ctx.setLogCallback((level, msg) -> {
            logBuf.append("[").append(level).append("] ").append(msg).append("\n");
            logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, level, msg);
        });

        String finalStatus;
        try {
            for (TableSyncConfig tc : tables) {
                if (Boolean.TRUE.equals(tc.getCreateTargetTable())) {
                    List<ColumnDef> cols = source.getColumnDefs(job.getSourceDb(), tc.getSourceTable());
                    tableSchemaService.ensureTargetTable(target, job.getTargetDb(), tc.getTargetTable(), cols);
                    ctx.log("INFO", "目标表就绪: " + tc.getTargetTable());
                }
            }

            SyncEngine engine = SyncEngineFactory.get(syncMode);
            engine.sync(ctx);
            finalStatus = ctx.getErrorCount().get() > 0 ? "FAILED" : "SUCCESS";

            if ("INCREMENTAL".equals(syncMode) && ctx.getErrorCount().get() == 0) {
                syncJobService.updateTableConfig(jobId, tables);
            }
        } catch (Exception e) {
            log.error("同步任务执行失败: jobId={}", jobId, e);
            ctx.log("ERROR", "执行失败: " + e.getMessage());
            finalStatus = "FAILED";
        }

        LocalDateTime end = LocalDateTime.now();
        if (exec != null) {
            exec.setStatus(finalStatus);
            exec.setEndTime(end);
            exec.setDuration((int) Duration.between(exec.getStartTime(), end).getSeconds());
            exec.setReadCount(ctx.getReadCount().get());
            exec.setWriteCount(ctx.getWriteCount().get());
            exec.setErrorCount(ctx.getErrorCount().get());
            String logStr = logBuf.toString();
            if (logStr.length() > MAX_LOG) {
                logStr = "[...日志过长，已截断前段...]\n" + logStr.substring(logStr.length() - MAX_LOG);
            }
            exec.setLog(logStr);
            taskExecutionMapper.updateById(exec);
        }

        // 回写任务状态并推送，前端列表据此实时刷新
        syncJobService.updateStatus(jobId, finalStatus);
        logBroadcastService.broadcastSyncStatus(jobId, finalStatus);
        logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, "INFO",
                "任务结束: " + finalStatus + "，读 " + ctx.getReadCount().get()
                + " 写 " + ctx.getWriteCount().get());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 应用关闭时优雅停线程池。 */
    @PreDestroy
    public void destroy() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
