package com.datanote.sync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnCdcDeadLetterMapper;
import com.datanote.mapper.DnCdcOffsetMapper;
import com.datanote.mapper.DnCdcSchemaHistoryMapper;
import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnDatasource;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.cdc.CdcStoreHolder;
import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.ConnectionManager;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.cdc.CdcSyncEngine;
import com.datanote.sync.schema.CreateColumnSupport;
import com.datanote.sync.schema.TableSchemaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CDC 同步引擎生命周期管理：按 jobId 登记运行中的 {@link CdcSyncEngine}，提供 start/stop/status。
 *
 * <p>应用启动时（{@link #init()}）先把 mapper 注入 {@link CdcStoreHolder}（供 Debezium 反射创建的
 * 存储类静态获取），再自动恢复所有 {@code sync_mode=CDC && status=RUNNING} 的任务（重启续跑）。
 */
@Slf4j
@Component
public class CdcEngineManager {

    private final DnCdcOffsetMapper offsetMapper;
    private final DnCdcSchemaHistoryMapper historyMapper;
    private final DnSyncJobMapper syncJobMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final ConnectionManager connectionManager;
    private final LogBroadcastService logBroadcastService;
    private final SyncJobService syncJobService;
    private final TableSchemaService tableSchemaService;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnCdcDeadLetterMapper deadLetterMapper;
    private final AuditLogService auditLogService;
    private final AlertService alertService;

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    /** database.server.id 基准值，避免与生产 slave 撞号；按需在配置中覆盖。 */
    @Value("${datanote.sync.cdc.server-id-base:5400}")
    private long serverIdBase;

    @Value("${datanote.sync.cdc.max-batch-size:2048}")
    private int cdcMaxBatchSize;

    @Value("${datanote.sync.cdc.max-queue-size:8192}")
    private int cdcMaxQueueSize;

    @Value("${datanote.sync.cdc.poll-interval-ms:500}")
    private int cdcPollIntervalMs;

    /** DS-M5：CDC 流式延迟告警阈值(ms)，0=关闭。 */
    @Value("${datanote.alert.cdc-lag-threshold-ms:0}")
    private long cdcLagThresholdMs;

    @Value("${datanote.sync.cdc.heartbeat-interval-ms:30000}")
    private int cdcHeartbeatMs;

    /** jobId -> 运行中的引擎实例。 */
    private final Map<Long, CdcSyncEngine> engines = new ConcurrentHashMap<>();
    /** jobId -> 当前运行对应的执行记录 id（dn_task_execution）。 */
    private final Map<Long, Long> jobExecId = new ConcurrentHashMap<>();
    /** 定期把运行中 CDC 引擎的计数/日志落库到执行记录。 */
    private ScheduledExecutorService flushScheduler;
    private static final long FLUSH_INTERVAL_SEC = 5L;

    public CdcEngineManager(DnCdcOffsetMapper offsetMapper,
                            DnCdcSchemaHistoryMapper historyMapper,
                            DnSyncJobMapper syncJobMapper,
                            DnDatasourceMapper datasourceMapper,
                            ConnectionManager connectionManager,
                            LogBroadcastService logBroadcastService,
                            SyncJobService syncJobService,
                            TableSchemaService tableSchemaService,
                            DnTaskExecutionMapper taskExecutionMapper,
                            DnCdcDeadLetterMapper deadLetterMapper,
                            AuditLogService auditLogService,
                            AlertService alertService) {
        this.offsetMapper = offsetMapper;
        this.historyMapper = historyMapper;
        this.syncJobMapper = syncJobMapper;
        this.datasourceMapper = datasourceMapper;
        this.connectionManager = connectionManager;
        this.logBroadcastService = logBroadcastService;
        this.syncJobService = syncJobService;
        this.tableSchemaService = tableSchemaService;
        this.taskExecutionMapper = taskExecutionMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.auditLogService = auditLogService;
        this.alertService = alertService;
    }

    /**
     * 注入存储桥接 + 恢复运行中的 CDC 任务。单任务失败不影响整体启动。
     *
     * <p>{@link CdcStoreHolder#init} 必须在所有 start 之前调用（供 Debezium 反射创建的存储类静态获取 mapper）。
     * 恢复时复用已 {@code synchronized} 的 {@link #start(Long)}，与外部并发 start 互斥，避免同一 jobId 双启动。
     */
    @PostConstruct
    public void init() {
        CdcStoreHolder.init(offsetMapper, historyMapper);
        startFlushScheduler();
        List<DnSyncJob> jobs = syncJobMapper.selectList(
                new LambdaQueryWrapper<DnSyncJob>()
                        .eq(DnSyncJob::getSyncMode, "CDC")
                        .eq(DnSyncJob::getStatus, "RUNNING"));
        log.info("CDC 启动恢复：待恢复任务数={}", jobs.size());
        for (DnSyncJob job : jobs) {
            try {
                start(job.getId());
            } catch (Exception e) {
                log.error("CDC 任务恢复失败 jobId={}", job.getId(), e);
            }
        }
    }

    /** 启动指定 CDC 任务（已运行则忽略）。 */
    public synchronized void start(Long jobId) {
        if (engines.containsKey(jobId)) {
            log.warn("CDC 任务已在运行，忽略 start jobId={}", jobId);
            return;
        }
        DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("同步任务不存在: " + jobId);
        }
        doStart(job);
    }

    private void doStart(DnSyncJob job) {
        Long jobId = job.getId();
        DnDatasource sourceDs = datasourceMapper.selectById(job.getSourceDsId());
        if (sourceDs == null) {
            throw new IllegalArgumentException("源数据源不存在: " + job.getSourceDsId());
        }
        DnDatasource targetDs = datasourceMapper.selectById(job.getTargetDsId());
        if (targetDs == null) {
            throw new IllegalArgumentException("目标数据源不存在: " + job.getTargetDsId());
        }
        List<TableSyncConfig> tables = syncJobService.parseTables(job);
        if (tables.isEmpty()) {
            throw new IllegalStateException("CDC 任务未配置任何表 jobId=" + jobId);
        }
        ensureTargetTables(job, tables);
        String targetType = targetDs.getType() == null ? "MYSQL" : targetDs.getType().toUpperCase();
        CdcSyncEngine engine = new CdcSyncEngine(job, sourceDs, tables,
                connectionManager, logBroadcastService, cryptoKey, targetType, serverIdBase,
                cdcMaxBatchSize, cdcMaxQueueSize, cdcPollIntervalMs, cdcHeartbeatMs, deadLetterMapper);
        engine.start();
        engines.put(jobId, engine);
        updateStatus(jobId, "RUNNING");
        createExecution(jobId);
        log.info("CDC 任务已启动 jobId={}", jobId);
    }

    private void ensureTargetTables(DnSyncJob job, List<TableSyncConfig> tables) {
        try {
            DbConnector source = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
            DbConnector target = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
            for (TableSyncConfig tc : tables) {
                if (Boolean.TRUE.equals(tc.getCreateTargetTable())) {
                    List<ColumnDef> cols = source.getColumnDefs(job.getSourceDb(), tc.getSourceTable());
                    cols = CreateColumnSupport.applyFieldMapping(cols, tc);
                    cols = CreateColumnSupport.appendSyncTsColumn(cols, job.getMarkSyncTs(), job.getSyncTsField());
                    tableSchemaService.ensureTargetTable(target, job.getTargetDb(), tc.getTargetTable(), cols);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("CDC 目标表自动建表失败 jobId=" + job.getId() + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop(Long jobId) {
        CdcSyncEngine engine = engines.remove(jobId);
        if (engine != null) {
            try { engine.stop(); } catch (Exception e) { log.warn("CDC 引擎停止异常,仍继续收尾 jobId={}", jobId, e); }
        }
        finalizeExecution(jobId, "STOPPED", engine);
        updateStatus(jobId, "STOPPED");
        log.info("CDC 任务已停止 jobId={}", jobId);
    }

    /**
     * 重置 CDC（高危）：停引擎、清该 job 的 offset 与 schema_history，使下次启动重新全量快照。
     * restart=true 时清理后立即重启（重新全量），false 时仅置 STOPPED 不重启。
     */
    public synchronized void resetAndRestart(Long jobId, boolean restart) {
        CdcSyncEngine engine = engines.remove(jobId);
        if (engine != null) {
            try { engine.stop(); } catch (Exception e) { log.warn("CDC 引擎停止异常,仍继续重置 jobId={}", jobId, e); }
            finalizeExecution(jobId, "STOPPED", engine);
        }
        offsetMapper.delete(new LambdaQueryWrapper<com.datanote.model.DnCdcOffset>()
                .eq(com.datanote.model.DnCdcOffset::getJobId, jobId));
        historyMapper.delete(new LambdaQueryWrapper<com.datanote.model.DnCdcSchemaHistory>()
                .eq(com.datanote.model.DnCdcSchemaHistory::getJobId, jobId));
        log.warn("CDC 已重置 offset+schema_history jobId={}", jobId);
        DnSyncJob job = syncJobMapper.selectById(jobId);
        auditLogService.record(jobId, job == null ? null : job.getJobName(), "RESET", "restart=" + restart);
        if (restart) { if (job != null) doStart(job); }
        else { updateStatus(jobId, "STOPPED"); }
    }

    /** 该任务是否正在运行。 */
    public boolean status(Long jobId) {
        CdcSyncEngine engine = engines.get(jobId);
        return engine != null && engine.isRunning();
    }

    /** 实时指标：运行状态 + 累计计数 + 真实 binlog 延迟(ms，读不到为 null)。 */
    public Map<String, Object> metrics(Long jobId) {
        Map<String, Object> m = new LinkedHashMap<>();
        CdcSyncEngine engine = engines.get(jobId);
        m.put("running", engine != null && engine.isRunning());
        if (engine != null) {
            m.put("readCount", engine.getReadCount());
            m.put("writeCount", engine.getWriteCount());
            m.put("errorCount", engine.getErrorCount());
            m.put("lagMs", engine.getStreamingLagMs());
            m.put("eventsSeen", engine.getEventsSeen());
            m.put("snapshotRunning", engine.getSnapshotRunning());
            m.put("snapshotCompleted", engine.getSnapshotCompleted());
        }
        return m;
    }

    /**
     * 触发指定表的增量快照（向源库 signal 表写 execute-snapshot 信号）。
     * 源库须已建 dn_cdc_signal 且任务开启 incrementalSnapshotEnabled。
     */
    public void triggerIncrementalSnapshot(Long jobId, List<String> sourceTables) {
        DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        if (sourceTables == null || sourceTables.isEmpty()) {
            throw new IllegalArgumentException("增量快照源表列表不能为空");
        }
        if (job.getIncrementalSnapshotEnabled() == null || job.getIncrementalSnapshotEnabled() != 1) {
            throw new IllegalStateException("该任务未开启增量快照");
        }
        DnDatasource ds = datasourceMapper.selectById(job.getSourceDsId());
        if (ds == null) {
            throw new IllegalArgumentException("源数据源不存在: " + job.getSourceDsId());
        }
        List<String> colls = new ArrayList<>();
        for (String t : sourceTables) {
            colls.add(job.getSourceDb() + "." + t.trim());
        }
        String data = com.datanote.sync.util.SignalSqlBuilder.executeSnapshotData(colls);
        String sql = com.datanote.sync.util.SignalSqlBuilder.insertSql(job.getSourceDb());
        // 直连源库（非池）写 signal 行
        String url = "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort() + "/" + job.getSourceDb()
                + "?useSSL=false&allowPublicKeyRetrieval=true";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, ds.getUsername(),
                com.datanote.util.CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey));
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, "execute-snapshot");
            ps.setString(3, data);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("写 signal 失败（源库需已建 dn_cdc_signal）: " + e.getMessage(), e);
        }
        log.info("已触发增量快照 jobId={} tables={}", jobId, sourceTables);
    }

    private void updateStatus(Long jobId, String status) {
        DnSyncJob update = new DnSyncJob();
        update.setId(jobId);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(update);
    }

    /** 启动定期刷新调度：把运行中引擎的计数/日志落库；探测自行退出的引擎并收尾为 FAILED。 */
    private void startFlushScheduler() {
        if (flushScheduler != null) {
            return;
        }
        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "datanote-cdc-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleWithFixedDelay(this::flushAll,
                FLUSH_INTERVAL_SEC, FLUSH_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** 遍历运行中引擎落库；对已自行退出（如 binlog 错误）的引擎收尾为 FAILED 并清理。 */
    private void flushAll() {
        for (Map.Entry<Long, CdcSyncEngine> entry : engines.entrySet()) {
            Long jobId = entry.getKey();
            CdcSyncEngine engine = entry.getValue();
            try {
                if (engine.isRunning()) {
                    updateExecutionRunning(jobId, engine);
                    // DS-M5：CDC 延迟超阈告警（AlertService 自带节流防刷屏）
                    if (cdcLagThresholdMs > 0) {
                        Long lag = engine.getStreamingLagMs(); // MBean 读不到时为 null,不可直接拆箱
                        if (lag != null && lag > cdcLagThresholdMs) {
                            alertService.alert(jobId, null, "CDC_LAG",
                                    "CDC 延迟 " + lag + "ms 超阈值 " + cdcLagThresholdMs + "ms");
                        }
                    }
                } else {
                    engines.remove(jobId, engine);
                    finalizeExecution(jobId, "FAILED", engine);
                    updateStatus(jobId, "FAILED");
                    alertService.alert(jobId, null, "CDC_FAILED", "CDC引擎异常退出");
                }
            } catch (Exception e) {
                log.warn("CDC 执行记录刷新失败 jobId={}", jobId, e);
            }
        }
    }

    /** 创建一条 RUNNING 执行记录并登记 jobId->execId（失败仅告警，不影响同步运行）。 */
    private void createExecution(Long jobId) {
        try {
            DnTaskExecution exec = new DnTaskExecution();
            exec.setSyncTaskId(jobId);
            exec.setTaskType("DbSync");
            exec.setTriggerType("cdc");
            exec.setStatus("RUNNING");
            exec.setStartTime(LocalDateTime.now());
            exec.setReadCount(0L);
            exec.setWriteCount(0L);
            exec.setErrorCount(0L);
            exec.setCreatedAt(LocalDateTime.now());
            taskExecutionMapper.insert(exec);
            jobExecId.put(jobId, exec.getId());
        } catch (Exception e) {
            log.warn("CDC 创建执行记录失败 jobId={}（不影响同步运行）", jobId, e);
        }
    }

    /** 运行中：仅更新计数与日志快照（状态保持 RUNNING）。 */
    private void updateExecutionRunning(Long jobId, CdcSyncEngine engine) {
        Long execId = jobExecId.get(jobId);
        if (execId == null) {
            return;
        }
        DnTaskExecution upd = new DnTaskExecution();
        upd.setId(execId);
        upd.setReadCount(engine.getReadCount());
        upd.setWriteCount(engine.getWriteCount());
        upd.setErrorCount(engine.getErrorCount());
        upd.setLog(engine.getLogSnapshot());
        taskExecutionMapper.updateById(upd);
    }

    /** 收尾：写终态、结束时间、耗时与最终计数/日志。jobExecId 原子摘除避免重复收尾。 */
    private void finalizeExecution(Long jobId, String status, CdcSyncEngine engine) {
        Long execId = jobExecId.remove(jobId);
        if (execId == null) {
            return;
        }
        try {
            DnTaskExecution exec = taskExecutionMapper.selectById(execId);
            if (exec == null) {
                return;
            }
            LocalDateTime end = LocalDateTime.now();
            exec.setStatus(status);
            exec.setEndTime(end);
            if (exec.getStartTime() != null) {
                exec.setDuration((int) Duration.between(exec.getStartTime(), end).getSeconds());
            }
            if (engine != null) {
                exec.setReadCount(engine.getReadCount());
                exec.setWriteCount(engine.getWriteCount());
                exec.setErrorCount(engine.getErrorCount());
                exec.setLog(engine.getLogSnapshot());
            }
            taskExecutionMapper.updateById(exec);
        } catch (Exception e) {
            log.warn("CDC 执行记录收尾失败 jobId={}", jobId, e);
        }
    }

    /** 应用关闭时停掉所有引擎。 */
    @PreDestroy
    public void destroy() {
        log.info("CDC 关闭：停止全部引擎，数量={}", engines.size());
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
        }
        for (Map.Entry<Long, CdcSyncEngine> entry : engines.entrySet()) {
            Long jobId = entry.getKey();
            try {
                entry.getValue().stop();
                finalizeExecution(jobId, "STOPPED", entry.getValue());
            } catch (Exception e) {
                log.error("关闭 CDC 引擎失败 jobId={}", jobId, e);
            }
        }
        engines.clear();
    }
}
