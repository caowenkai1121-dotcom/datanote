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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步执行入口：被手动触发或调度调用。支持 FULL/INCREMENTAL，自动建表，增量断点回写。
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

    /** 执行一个同步任务。返回执行记录 id。 */
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
        String syncMode = job.getSyncMode() == null ? "FULL" : job.getSyncMode().toUpperCase();

        SyncContext ctx = new SyncContext();
        ctx.setJobId(jobId);
        ctx.setExecutionId(exec.getId());
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

        logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, "INFO",
                "任务结束: " + finalStatus + "，读 " + ctx.getReadCount().get()
                + " 写 " + ctx.getWriteCount().get());
        return exec.getId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
