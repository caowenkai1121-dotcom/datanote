package com.datanote.sync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnCdcOffsetMapper;
import com.datanote.mapper.DnCdcSchemaHistoryMapper;
import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.model.DnDatasource;
import com.datanote.model.DnSyncJob;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.cdc.CdcStoreHolder;
import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.ConnectionManager;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.cdc.CdcSyncEngine;
import com.datanote.sync.schema.TableSchemaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    /** database.server.id 基准值，避免与生产 slave 撞号；按需在配置中覆盖。 */
    @Value("${datanote.sync.cdc.server-id-base:5400}")
    private long serverIdBase;

    /** jobId -> 运行中的引擎实例。 */
    private final Map<Long, CdcSyncEngine> engines = new ConcurrentHashMap<>();

    public CdcEngineManager(DnCdcOffsetMapper offsetMapper,
                            DnCdcSchemaHistoryMapper historyMapper,
                            DnSyncJobMapper syncJobMapper,
                            DnDatasourceMapper datasourceMapper,
                            ConnectionManager connectionManager,
                            LogBroadcastService logBroadcastService,
                            SyncJobService syncJobService,
                            TableSchemaService tableSchemaService) {
        this.offsetMapper = offsetMapper;
        this.historyMapper = historyMapper;
        this.syncJobMapper = syncJobMapper;
        this.datasourceMapper = datasourceMapper;
        this.connectionManager = connectionManager;
        this.logBroadcastService = logBroadcastService;
        this.syncJobService = syncJobService;
        this.tableSchemaService = tableSchemaService;
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
                connectionManager, logBroadcastService, cryptoKey, targetType, serverIdBase);
        engine.start();
        engines.put(jobId, engine);
        updateStatus(jobId, "RUNNING");
        log.info("CDC 任务已启动 jobId={}", jobId);
    }

    private void ensureTargetTables(DnSyncJob job, List<TableSyncConfig> tables) {
        try {
            DbConnector source = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
            DbConnector target = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
            for (TableSyncConfig tc : tables) {
                if (Boolean.TRUE.equals(tc.getCreateTargetTable())) {
                    List<ColumnDef> cols = source.getColumnDefs(job.getSourceDb(), tc.getSourceTable());
                    tableSchemaService.ensureTargetTable(target, job.getTargetDb(), tc.getTargetTable(), cols);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("CDC 目标表自动建表失败 jobId=" + job.getId() + ": " + e.getMessage(), e);
        }
    }

    /** 停止指定 CDC 任务（未运行则仅更新状态）。 */
    public synchronized void stop(Long jobId) {
        CdcSyncEngine engine = engines.remove(jobId);
        if (engine != null) {
            engine.stop();
        }
        updateStatus(jobId, "STOPPED");
        log.info("CDC 任务已停止 jobId={}", jobId);
    }

    /** 该任务是否正在运行。 */
    public boolean status(Long jobId) {
        CdcSyncEngine engine = engines.get(jobId);
        return engine != null && engine.isRunning();
    }

    private void updateStatus(Long jobId, String status) {
        DnSyncJob update = new DnSyncJob();
        update.setId(jobId);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(update);
    }

    /** 应用关闭时停掉所有引擎。 */
    @PreDestroy
    public void destroy() {
        log.info("CDC 关闭：停止全部引擎，数量={}", engines.size());
        for (Map.Entry<Long, CdcSyncEngine> entry : engines.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception e) {
                log.error("关闭 CDC 引擎失败 jobId={}", entry.getKey(), e);
            }
        }
        engines.clear();
    }
}
