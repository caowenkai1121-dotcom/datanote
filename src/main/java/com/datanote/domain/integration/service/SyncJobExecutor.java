package com.datanote.domain.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.integration.mapper.DnSyncErrorRowMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.integration.model.DnSyncErrorRow;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.common.LogBroadcastService;
import com.datanote.domain.integration.connector.ColumnDef;
import com.datanote.domain.integration.connector.DbConnector;
import com.datanote.domain.integration.dto.SyncContext;
import com.datanote.domain.integration.dto.TableSyncConfig;
import com.datanote.domain.integration.engine.SyncEngine;
import com.datanote.domain.integration.engine.SyncEngineFactory;
import com.datanote.domain.integration.schema.CreateColumnSupport;
import com.datanote.domain.integration.schema.TableSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
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
    private final DnSyncJobMapper syncJobMapper;
    private final DnSyncErrorRowMapper syncErrorRowMapper;
    private final com.datanote.domain.integration.connector.ConnectionManager connectionManager;
    private final AuditLogService auditLogService;
    private final AlertService alertService;
    private final com.datanote.domain.integration.schema.SchemaDriftService schemaDriftService;

    private static final String TASK_TYPE = "DbSync";
    private static final int MAX_LOG = 1_000_000;

    /** 优先级线程池大小（可配置，资源隔离）。 */
    @org.springframework.beans.factory.annotation.Value("${datanote.sync.executor.pool-size:4}")
    private int poolSize;
    /** DS-M5：单次运行坏行(脏数据)堆积告警阈值，0=关闭。 */
    @org.springframework.beans.factory.annotation.Value("${datanote.alert.dlq-threshold:0}")
    private long dlqThreshold;
    /** 后台执行优先级线程池（FULL/INCREMENTAL 一次性任务），高 priority 任务先跑。在 @PostConstruct 初始化。 */
    private ThreadPoolExecutor pool;
    /** 超时调度器：到点请求停止运行中的任务。 */
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    /** 正在运行的 jobId 集合，防同一任务重复运行。 */
    private final Set<Long> runningJobs = ConcurrentHashMap.newKeySet();
    /** 运行中的 jobId -> SyncContext，供停止/超时设置停止标志。 */
    private final Map<Long, SyncContext> runningContexts = new ConcurrentHashMap<>();

    /**
     * 启动补偿：进程上次异常退出会把非 CDC 的 RUNNING 任务及其执行记录永久卡在 RUNNING。
     * 启动时把它们置 FAILED（CDC 任务由 CdcEngineManager 续跑，按 syncMode 排除，不受运行态影响）。
     */
    @PostConstruct
    public void recoverOrphans() {
        // 先初始化优先级线程池（一个类仅一个 @PostConstruct 生效，故并入此处）
        pool = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "datanote-sync-exec"); t.setDaemon(true); return t; });
        try {
            List<DnSyncJob> running = syncJobMapper.selectList(
                    new LambdaQueryWrapper<DnSyncJob>().eq(DnSyncJob::getStatus, "RUNNING"));
            Set<Long> orphanJobIds = new HashSet<>();
            for (DnSyncJob j : running) {
                String mode = j.getSyncMode() == null ? "" : j.getSyncMode().toUpperCase();
                if (!"CDC".equals(mode)) {
                    orphanJobIds.add(j.getId());
                    syncJobService.updateStatus(j.getId(), "FAILED");
                }
            }
            if (!orphanJobIds.isEmpty()) {
                List<DnTaskExecution> execs = taskExecutionMapper.selectList(
                        new LambdaQueryWrapper<DnTaskExecution>()
                                .eq(DnTaskExecution::getTaskType, TASK_TYPE)
                                .eq(DnTaskExecution::getStatus, "RUNNING")
                                .in(DnTaskExecution::getSyncTaskId, orphanJobIds));
                LocalDateTime now = LocalDateTime.now();
                for (DnTaskExecution e : execs) {
                    e.setStatus("FAILED");
                    e.setEndTime(now);
                    String note = "[进程重启，执行被中断]";
                    e.setLog(e.getLog() == null ? note : e.getLog() + "\n" + note);
                    taskExecutionMapper.updateById(e);
                }
                log.warn("[SyncJobExecutor] 启动补偿 {} 个孤儿 RUNNING 任务为 FAILED", orphanJobIds.size());
            }
        } catch (Exception ex) {
            log.warn("[SyncJobExecutor] 孤儿 RUNNING 补偿失败", ex);
        }
    }

    /** 请求停止运行中的 FULL/INCREMENTAL 任务（手动）。返回是否命中运行中的任务。 */
    public boolean stop(Long jobId) {
        SyncContext ctx = runningContexts.get(jobId);
        if (ctx == null) {
            return false;
        }
        ctx.requestStop("manual");
        ctx.log("WARN", "收到停止请求");
        DnSyncJob job = syncJobService.getById(jobId);
        auditLogService.record(jobId, job == null ? null : job.getJobName(), "STOP", "manual");
        return true;
    }

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
        exec.setAttempt(1);
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
        final int timeoutSec = job.getTimeoutSeconds() == null ? 0 : job.getTimeoutSeconds();
        int priority = job.getPriority() == null ? 5 : job.getPriority();
        pool.execute(new com.datanote.domain.integration.util.PrioritizedTask(priority, jobId, () -> {
            try {
                doRunWithRetry(job, triggerType, exec);
            } finally {
                runningJobs.remove(jobId);
                runningContexts.remove(jobId);
            }
        }));
        // 超时终止：到点若仍在跑（runningContexts 仍有上下文，跑完 finally 已 remove），请求停止
        // （引擎在分页边界检查后中断，置 FAILED）
        if (timeoutSec > 0) {
            timeoutScheduler.schedule(() -> {
                SyncContext ctx = runningContexts.get(jobId);
                if (ctx != null) {
                    ctx.requestStop("timeout");
                    ctx.log("WARN", "任务超时(" + timeoutSec + "s)，请求停止");
                }
            }, timeoutSec, TimeUnit.SECONDS);
        }
        auditLogService.record(jobId, job.getJobName(), "RUN", "trigger=" + triggerType);
        return execId;
    }

    /**
     * 真正的同步执行体（后台线程）：完成本次执行并 finalize 执行记录。
     * 返回 {@link FailureInfo}（最终状态 + 是否可重试）；终态的 dn_sync_job.status 由 {@link #doRunWithRetry} 在不再重试时回写。
     */
    private FailureInfo doRun(DnSyncJob job, String triggerType, DnTaskExecution exec, int attempt) {
        Long jobId = job.getId();
        Long execId = exec.getId();
        exec.setAttempt(attempt);

        StringBuilder logBuf = new StringBuilder();
        String syncMode = job.getSyncMode() == null ? "FULL" : job.getSyncMode().toUpperCase();

        SyncContext ctx = new SyncContext();
        ctx.setJobId(jobId);
        ctx.setExecutionId(execId);
        ctx.setSourceDb(job.getSourceDb());
        ctx.setTargetDb(job.getTargetDb());
        ctx.setWriteMode(job.getWriteMode() == null ? "UPSERT" : job.getWriteMode());
        ctx.setBatchSize(job.getBatchSize() == null ? 1000 : job.getBatchSize());
        // 迭代V3：同步时间戳标记透传给引擎
        ctx.setMarkSyncTs(job.getMarkSyncTs());
        ctx.setSyncTsField(job.getSyncTsField());
        ctx.setGlobalPreSql(job.getPreSql());
        ctx.setGlobalPostSql(job.getPostSql());
        ctx.setErrorLimitRows(job.getErrorLimitRows());
        ctx.setErrorLimitRatio(job.getErrorLimitRatio() == null ? null : job.getErrorLimitRatio().doubleValue());
        String rlMode = job.getRateLimitMode();
        if (("ROWS".equalsIgnoreCase(rlMode) || "BATCHES".equalsIgnoreCase(rlMode))
                && job.getRateLimitValue() != null && job.getRateLimitValue() > 0) {
            ctx.setRateLimiter(new com.datanote.domain.integration.util.RateLimiter(job.getRateLimitValue(), System.nanoTime()));
        }
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
        // 增量断点按表持久化（某表成功即回写，避免后续表失败丢已成功表断点）
        ctx.setCheckpointCallback(tc -> syncJobService.updateTableCheckpoint(jobId, tc));
        // M2b：全量 chunk 游标加载/保存/清除接入 dn_sync_chunk_checkpoint
        ctx.setChunkLoad(t -> syncJobService.loadChunkCursor(jobId, t));
        ctx.setChunkSave((t, v) -> syncJobService.saveChunkCursor(jobId, t, v));
        ctx.setChunkClear(t -> syncJobService.clearChunkCursor(jobId, t));
        // DS-M1：坏行落 DLQ(dn_sync_error_row)，写失败不阻断同步
        ctx.setBadRowSink((table, row, err) -> {
            try {
                DnSyncErrorRow er = new DnSyncErrorRow();
                er.setJobId(jobId);
                er.setRunId(execId);
                er.setSourceTable(table);
                er.setRawRow(com.alibaba.fastjson.JSON.toJSONString(row));
                er.setErrorMsg(err == null ? null : (err.length() > 2000 ? err.substring(0, 2000) : err));
                er.setStage(syncMode);
                er.setAttempt(attempt);
                syncErrorRowMapper.insert(er);
            } catch (Exception ignore) { /* DLQ 落表失败不影响主流程 */ }
        });
        // DS-M4：写入通道=STREAM_LOAD 且目标为 Doris/StarRocks 时装配 Stream Load 通道（失败回退 JDBC）
        String wc = job.getWriteChannel();
        if ("STREAM_LOAD".equalsIgnoreCase(wc)) {
            String tt = target.getDatabaseType();
            if ("DORIS".equalsIgnoreCase(tt) || "STARROCKS".equalsIgnoreCase(tt)) {
                try {
                    com.datanote.domain.integration.connector.StreamLoadTarget slt = connectionManager.resolveStreamLoadTarget(job.getTargetDsId());
                    String tdb = job.getTargetDb();
                    java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
                    ctx.setStreamLoadChannel((tbl, cols, rows) -> com.datanote.domain.integration.engine.StreamLoadWriter.load(
                            slt, tdb, tbl, cols, rows,
                            com.datanote.domain.integration.engine.StreamLoadWriter.buildLabel(jobId, execId, tbl, seq.incrementAndGet())));
                    ctx.log("INFO", "写入通道=STREAM_LOAD(Doris原生导入,失败自动回退JDBC)");
                } catch (Exception e) {
                    log.warn("Stream Load 通道初始化失败,回退JDBC jobId={}", jobId, e);
                }
            } else {
                ctx.log("WARN", "写入通道=STREAM_LOAD 但目标非 Doris/StarRocks(" + tt + "),按 JDBC 执行");
            }
        }
        // 注册运行上下文，供停止/超时设置停止标志
        runningContexts.put(jobId, ctx);

        String finalStatus;
        Exception caught = null;
        try {
            // DS-M7：漂移阻断的表不进入引擎；仅对放行表建表，并以放行表列表驱动引擎
            List<TableSyncConfig> toSync = new java.util.ArrayList<>();
            for (TableSyncConfig tc : tables) {
                // 源 schema 危险漂移(删列/改类型/改主键)→告警+跳过该表；新增列放行
                List<ColumnDef> srcCols = source.getColumnDefs(job.getSourceDb(), tc.getSourceTable());
                if (schemaDriftService.checkAndTrack(jobId, job.getJobName(), tc.getSourceTable(), srcCols)) {
                    ctx.log("WARN", "源表 " + tc.getSourceTable() + " schema 危险漂移，跳过同步（重置快照后恢复）");
                    continue;
                }
                if (Boolean.TRUE.equals(tc.getCreateTargetTable())) {
                    // 复用上面已读取的源列定义
                    List<ColumnDef> cols = CreateColumnSupport.applyFieldMapping(srcCols, tc);
                    // 迭代V3：标记同步时间戳时，自动建表追加一列 syncTsField（DATETIME，可空，非主键）
                    cols = CreateColumnSupport.appendSyncTsColumn(cols, job.getMarkSyncTs(), job.getSyncTsField());
                    tableSchemaService.ensureTargetTable(target, job.getTargetDb(), tc.getTargetTable(), cols);
                    ctx.log("INFO", "目标表就绪: " + tc.getTargetTable());
                }
                toSync.add(tc);
            }
            // 仅放行表交给引擎（漂移阻断表既不建表也不同步、不推进断点）
            ctx.setTables(toSync);

            SyncEngine engine = SyncEngineFactory.get(syncMode);
            engine.sync(ctx);
            if (ctx.getStopped().get()) {
                // 手动停止 -> STOPPED；超时停止 -> FAILED
                finalStatus = "timeout".equals(ctx.getStopReason()) ? "FAILED" : "STOPPED";
            } else {
                finalStatus = ctx.getErrorCount().get() > 0 ? "FAILED" : "SUCCESS";
            }

            // 成功表的断点已在引擎内按表回写；无错误且未中断时再整体回写一次兜底
            if ("INCREMENTAL".equals(syncMode) && ctx.getErrorCount().get() == 0 && !ctx.getStopped().get()) {
                syncJobService.updateTableConfig(jobId, tables);
            }
        } catch (Exception e) {
            log.error("同步任务执行失败: jobId={}", jobId, e);
            ctx.log("ERROR", "执行失败: " + e.getMessage());
            finalStatus = "FAILED";
            caught = e;
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

        logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, "INFO",
                "任务结束: " + finalStatus + "，读 " + ctx.getReadCount().get()
                + " 写 " + ctx.getWriteCount().get());

        // DS-M5：坏行(脏数据)堆积超阈告警
        long dirty = ctx.getDirtyCount().get();
        if (dlqThreshold > 0 && dirty > dlqThreshold) {
            alertService.alert(jobId, job.getJobName(), "DLQ_HIGH", "本次坏行数 " + dirty + " 超阈值 " + dlqThreshold);
        }

        // 终态的 dn_sync_job.status 由 doRunWithRetry 决定（可能还要重试）；这里仅返回结果。
        // 仅瞬时异常可重试；errorCount>0 导致的 FAILED（caught==null）/手动停止/超时 不重试。
        boolean retryable = caught != null && com.datanote.domain.integration.util.ErrorClassifier.isTransient(caught);
        return new FailureInfo(finalStatus, retryable);
    }

    /** 单次执行结果：最终状态 + 是否可（瞬时错误）重试。 */
    private static final class FailureInfo {
        final String finalStatus;
        final boolean retryable;
        FailureInfo(String s, boolean r) { finalStatus = s; retryable = r; }
    }

    /** 带退避重试的执行：失败且可重试时按退避策略重试，终态时回写 dn_sync_job.status 并推送。 */
    private void doRunWithRetry(DnSyncJob job, String triggerType, DnTaskExecution firstExec) {
        int maxRetries = job.getRetryTimes() == null ? 0 : Math.max(0, job.getRetryTimes());
        String btype = job.getRetryBackoffType();
        int base = job.getRetryBackoffDelay() == null ? 5 : job.getRetryBackoffDelay();
        int attempt = 1;
        DnTaskExecution exec = firstExec;
        while (true) {
            FailureInfo fi = doRun(job, triggerType, exec, attempt);
            if (!fi.retryable || attempt > maxRetries) {
                syncJobService.updateStatus(job.getId(), fi.finalStatus);
                logBroadcastService.broadcastSyncStatus(job.getId(), fi.finalStatus);
                if ("FAILED".equals(fi.finalStatus)) {
                    alertService.alert(job.getId(), job.getJobName(), "FAILED", "同步失败");
                }
                return;
            }
            int delay = com.datanote.domain.integration.util.BackoffCalculator.delaySeconds(attempt, btype, base, 300);
            logBroadcastService.broadcastTaskLog(job.getId(), TASK_TYPE, "WARN",
                "瞬时错误,第" + attempt + "次失败,等待" + delay + "s后重试");
            try { Thread.sleep(delay * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            attempt++;
            exec = newExecution(job.getId(), triggerType, attempt);
        }
    }

    /** 为重试创建一条新的 RUNNING 执行记录并置任务 RUNNING、推状态。 */
    private DnTaskExecution newExecution(Long jobId, String triggerType, int attempt) {
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
        exec.setAttempt(attempt);
        taskExecutionMapper.insert(exec);
        syncJobService.updateStatus(jobId, "RUNNING");
        logBroadcastService.broadcastSyncStatus(jobId, "RUNNING");
        return exec;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 应用关闭时优雅停线程池。 */
    @PreDestroy
    public void destroy() {
        timeoutScheduler.shutdownNow();
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
