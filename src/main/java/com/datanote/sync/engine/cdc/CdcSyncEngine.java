package com.datanote.sync.engine.cdc;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.datanote.model.DnDatasource;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.connector.ConnectionManager;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.connector.TableMeta;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.util.WriteSqlBuilder;
import com.datanote.util.CryptoUtil;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 基于 Debezium 嵌入式引擎的 MySQL CDC 同步引擎（单个同步任务一个实例）。
 *
 * <p>用 {@link DebeziumEngine}（{@link Json} 格式）监听源库 binlog，把每条变更事件
 * 按 op（c/r/u/d）映射为对目标表的幂等写入：c/r/u 走 UPSERT，d 走 DELETE。
 * 写入复用 {@link WriteSqlBuilder} + {@link ConnectionManager}（PreparedStatement 参数化）。
 *
 * <p>offset / schema 历史持久化到 MySQL（{@code offset.storage} / {@code database.history}
 * 指向 {@code com.datanote.sync.cdc} 下的存储类，靠 {@code datanote.cdc.job.id} 隔离任务）。
 *
 * <p><b>1.9.7 配置要点</b>：逻辑名/topic 前缀用 {@code database.server.name}（非 2.0+ 的
 * {@code topic.prefix}）；schema 历史类配置键用 {@code database.history}（非 {@code schema.history.internal}）。
 *
 * <p>第一版：要求目标表已存在（不自动建表）；仅支持 MySQL 源。
 */
public class CdcSyncEngine {

    private static final Logger log = LoggerFactory.getLogger(CdcSyncEngine.class);

    /** Debezium 配置：jobId 透传键，存储类据此隔离任务。 */
    public static final String JOB_ID_CONFIG = "datanote.cdc.job.id";

    private final DnSyncJob job;
    private final DnDatasource sourceDs;
    private final List<TableSyncConfig> tables;
    private final ConnectionManager connectionManager;
    private final com.datanote.common.LogBroadcastService logBroadcastService;
    private final String cryptoKey;
    private final String targetDatabaseType;
    /** database.server.id 基准值（可配置，避免与生产 slave 撞号）。 */
    private final long serverIdBase;
    /** Debezium 背压配置。 */
    private final int maxBatchSize;
    private final int maxQueueSize;
    private final int pollIntervalMs;
    /** Debezium 心跳间隔毫秒（>0 才启用，用于低频源库及时推进 offset）。 */
    private final int heartbeatIntervalMs;
    /** 死信 Mapper（坏事件落库，可为 null 表示不落库）。 */
    private final com.datanote.mapper.DnCdcDeadLetterMapper deadLetterMapper;

    /** 源表名 -> 该表同步配置（含目标表名）。 */
    private final Map<String, TableSyncConfig> tableMap = new ConcurrentHashMap<>();
    /** "目标库.目标表" -> 主键列（缓存，避免每条事件查 information_schema；带库名前缀防多库同名表取错）。 */
    private final Map<String, List<String>> pkCache = new ConcurrentHashMap<>();
    /** "目标库.目标表" -> 目标列集合（DDL 同步漂移检测缓存，ADD COLUMN 后失效）。 */
    private final Map<String, java.util.Set<String>> targetColsCache = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 累计计数（供 {@link com.datanote.sync.service.CdcEngineManager} 定期落库到执行记录）。 */
    private final AtomicLong readCount = new AtomicLong();
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    /** 持久化日志缓冲（带上限，供执行历史回放；与实时 WebSocket 广播并行维护）。 */
    private final StringBuilder logBuffer = new StringBuilder();
    private final Object logLock = new Object();
    private static final int MAX_LOG_BUFFER = 200_000;

    private volatile DebeziumEngine<ChangeEvent<String, String>> engine;
    private volatile ExecutorService executor;
    /** 目标库连接器（按目标库取主键元数据用，MySQL 协议族）。 */
    private MysqlConnector targetConnector;

    public CdcSyncEngine(DnSyncJob job,
                         DnDatasource sourceDs,
                         List<TableSyncConfig> tables,
                         ConnectionManager connectionManager,
                         com.datanote.common.LogBroadcastService logBroadcastService,
                         String cryptoKey,
                         String targetDatabaseType,
                         long serverIdBase,
                         int maxBatchSize,
                         int maxQueueSize,
                         int pollIntervalMs,
                         int heartbeatIntervalMs,
                         com.datanote.mapper.DnCdcDeadLetterMapper deadLetterMapper) {
        this.job = job;
        this.sourceDs = sourceDs;
        this.tables = tables;
        this.connectionManager = connectionManager;
        this.logBroadcastService = logBroadcastService;
        this.cryptoKey = cryptoKey;
        this.targetDatabaseType = targetDatabaseType == null ? "MYSQL" : targetDatabaseType;
        this.serverIdBase = serverIdBase;
        this.maxBatchSize = maxBatchSize;
        this.maxQueueSize = maxQueueSize;
        this.pollIntervalMs = pollIntervalMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.deadLetterMapper = deadLetterMapper;
        for (TableSyncConfig tc : tables) {
            if (tc.getSourceTable() != null) {
                tableMap.put(tc.getSourceTable(), tc);
            }
        }
    }

    /** 阻塞启动 Debezium 引擎（在专用单线程内运行直至 stop）。 */
    public void start() {
        if (running.get()) {
            log.warn("CDC 引擎已在运行 jobId={}", job.getId());
            return;
        }
        // 逻辑删除模式必须配置标记列，否则启动即失败（拒绝静默回退物理删除误删生产数据）
        if ("LOGICAL".equalsIgnoreCase(job.getDeleteMode())
                && (job.getLogicalDeleteField() == null || job.getLogicalDeleteField().trim().isEmpty())) {
            throw new IllegalStateException("CDC 逻辑删除模式必须配置 logicalDeleteField，jobId=" + job.getId());
        }
        // fat-jar 下 Debezium 用 Class.forName 加载连接器/存储类，需把上下文类加载器设为本类加载器
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        this.targetConnector = new MysqlConnector(connectionManager, job.getTargetDsId(), job.getTargetDb(), targetDatabaseType);

        Properties props = buildProps();
        Long jobId = job.getId();
        this.engine = DebeziumEngine.create(Json.class)
                .using(props)
                .using(getClass().getClassLoader())
                .notifying(this::handleBatch)
                // 引擎线程内启动失败/正常结束时回置 running，避免状态假象且无法重启
                .using((success, message, error) -> {
                    running.set(false);
                    if (!success) {
                        log.error("CDC引擎退出: jobId={}, msg={}", jobId, message, error);
                        broadcast("ERROR", "CDC 引擎退出: " + message);
                    } else {
                        log.info("CDC引擎正常停止: jobId={}", jobId);
                    }
                })
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "datanote-cdc-" + job.getId());
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        broadcast("INFO", "CDC 引擎启动，源库=" + job.getSourceDb()
                + "，捕获表=" + tableMap.keySet() + "，目标库=" + job.getTargetDb());
        executor.execute(engine);
    }

    /** 构建 Debezium 1.9.7 配置（自行查证后的键名）。 */
    private Properties buildProps() {
        Long jobId = job.getId();
        Properties props = new Properties();
        // 引擎与连接器
        props.setProperty("name", "datanote-cdc-" + jobId);
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        // 源库连接（密码解密）
        props.setProperty("database.hostname", sourceDs.getHost());
        props.setProperty("database.port", String.valueOf(sourceDs.getPort()));
        props.setProperty("database.user", sourceDs.getUsername());
        props.setProperty("database.password", CryptoUtil.decryptSafe(sourceDs.getPassword(), cryptoKey));
        // server.id 需在源库唯一（同一源库多任务避免撞号）。直接 base+jobId（不取模，否则
        // jobId 与 jobId+100000 取模后相同会撞号互踢）；server_id 为无符号 32 位，溢出则回绕到合法区间
        long serverId = serverIdBase + jobId;
        if (serverId > 4294967295L || serverId < 1L) {
            serverId = serverIdBase + (jobId % (4294967295L - serverIdBase));
        }
        props.setProperty("database.server.id", String.valueOf(serverId));
        // 1.9.7：逻辑名/topic 前缀键为 database.server.name（2.0+ 才是 topic.prefix）
        props.setProperty("database.server.name", "datanote_cdc_" + jobId);
        // 捕获范围
        props.setProperty("database.include.list", job.getSourceDb());
        props.setProperty("table.include.list", buildTableIncludeList());
        props.setProperty("snapshot.mode", "initial");
        // offset 存储（持久化到 MySQL）
        props.setProperty("offset.storage", "com.datanote.sync.cdc.JdbcOffsetBackingStore");
        props.setProperty("offset.flush.interval.ms", "1000");
        // 1.9.7：schema 历史类配置键为 database.history（2.0+ 才是 schema.history.internal）
        props.setProperty("database.history", "com.datanote.sync.cdc.JdbcSchemaHistory");
        // 透传 jobId 给两个存储类（按任务隔离）
        props.setProperty(JOB_ID_CONFIG, String.valueOf(jobId));
        props.setProperty("database.history." + JOB_ID_CONFIG, String.valueOf(jobId));
        // 删除事件保留有效记录（不产生 tombstone null 记录，简化下游处理）
        props.setProperty("tombstones.on.delete", "false");
        // 背压配置
        props.setProperty("max.batch.size", String.valueOf(maxBatchSize));
        props.setProperty("max.queue.size", String.valueOf(maxQueueSize));
        props.setProperty("poll.interval.ms", String.valueOf(pollIntervalMs));
        if (heartbeatIntervalMs > 0) props.setProperty("heartbeat.interval.ms", String.valueOf(heartbeatIntervalMs));
        // M4b 增量快照（默认关）：仅开关开时加 signal 配置，关时上面 table.include.list 等保持原样
        if (job.getIncrementalSnapshotEnabled() != null && job.getIncrementalSnapshotEnabled() == 1) {
            String signalColl = job.getSourceDb() + ".dn_cdc_signal";
            props.setProperty("signal.data.collection", signalColl);
            // signal 表需在捕获范围：并入 table.include.list
            props.setProperty("table.include.list", buildTableIncludeList() + "," + signalColl);
            props.setProperty("read.only", "true");
        }
        return props;
    }

    /** 源库.各源表，逗号分隔（table.include.list 需库名前缀）。 */
    private String buildTableIncludeList() {
        return tableMap.keySet().stream()
                .map(t -> job.getSourceDb() + "." + t)
                .collect(Collectors.joining(","));
    }

    /** 一条已解析并路由到目标表的待应用变更。 */
    private static final class Apply {
        final TableSyncConfig tc;
        final ChangeOp change;
        Apply(TableSyncConfig tc, ChangeOp change) {
            this.tc = tc;
            this.change = change;
        }
    }

    /**
     * 批量处理 Debezium 变更事件（ChangeConsumer 语义）。
     *
     * <p>关键正确性：解析失败/未配置表等<b>不可重试的脏数据</b>跳过；可应用的变更在<b>单连接单事务</b>内按序写入，
     * 整批 commit 成功后才 {@code markProcessed + markBatchFinished} 推进 offset；写库失败则 rollback 并抛出，
     * <b>不推进 offset</b>，Debezium 重投整批 —— 配合幂等 UPSERT/DELETE 实现 at-least-once，杜绝目标库瞬时
     * 故障窗口的变更被静默丢失（旧实现 .notifying(Consumer) 吞写库异常但 offset 照常 flush，会永久丢数）。
     */
    private void handleBatch(List<ChangeEvent<String, String>> records,
                             DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer)
            throws InterruptedException {
        // 1) 解析路由，保序收集可应用变更；脏数据/未配置表跳过（不可重试，不影响 offset 推进）
        List<Apply> applies = new ArrayList<>();
        for (ChangeEvent<String, String> rec : records) {
            String value = rec.value();
            if (value == null || value.isEmpty()) {
                continue; // tombstone 等空记录
            }
            ChangeOp change;
            try {
                change = parseChange(value);
            } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("CDC 解析失败 jobId={} value={}", job.getId(), abbreviate(value), e);
                saveDeadLetter(null, null, value, e.getMessage(), "PARSE");
                continue;
            }
            if (change == null) {
                continue;
            }
            TableSyncConfig tc = tableMap.get(change.sourceTable);
            if (tc == null) {
                continue; // 未配置的源表
            }
            applies.add(new Apply(tc, change));
        }

        // 2) 单连接单事务按序写入；失败回滚并抛出（触发整批重试，offset 不推进）
        if (!applies.isEmpty()) {
            try (Connection conn = connectionManager.getConnection(job.getTargetDsId(), job.getTargetDb())) {
                boolean prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    for (Apply a : applies) {
                        applyChange(conn, a.tc, a.change);
                    }
                    conn.commit();
                    readCount.addAndGet(applies.size());
                } catch (Exception e) {
                    conn.rollback();
                    errorCount.incrementAndGet();
                    broadcast("ERROR", "CDC 批写入失败，将重试整批: " + e.getMessage());
                    throw new RuntimeException("CDC batch apply failed, will retry", e);
                } finally {
                    conn.setAutoCommit(prevAuto);
                }
            } catch (RuntimeException re) {
                throw re; // 上抛触发 Debezium 重投本批
            } catch (Exception e) {
                // 取连接失败等：同样需重试，不推进 offset
                throw new RuntimeException("CDC batch connection failed, will retry", e);
            }
        }

        // 3) 整批成功（或无可应用变更）：推进 offset
        for (ChangeEvent<String, String> rec : records) {
            committer.markProcessed(rec);
        }
        committer.markBatchFinished();
    }

    /** 把一条变更写入目标库（复用调用方事务连接）。若该表配置了字段映射，先按 source-&gt;target 投影 after/before。 */
    private void applyChange(Connection conn, TableSyncConfig tc, ChangeOp change) throws Exception {
        String targetTable = tc.getTargetTable();
        String targetDb = job.getTargetDb();
        switch (change.opType) {
            case INSERT:
                Map<String, Object> insertProj = projectByFields(change.after, tc);
                syncDriftColumns(conn, targetDb, targetTable, change.sourceTable, insertProj);
                writeUpsert(conn, targetDb, targetTable, insertProj);
                break;
            case UPDATE:
                Map<String, Object> beforeProj = projectByFields(change.before, tc);
                Map<String, Object> afterProj = projectByFields(change.after, tc);
                // 主键变更：先按旧主键删旧行，再 UPSERT 新行，避免旧主键行残留为孤儿/重复数据
                if (isPkChanged(targetDb, targetTable, beforeProj, afterProj)) {
                    writeDelete(conn, targetDb, targetTable, beforeProj);
                }
                syncDriftColumns(conn, targetDb, targetTable, change.sourceTable, afterProj);
                writeUpsert(conn, targetDb, targetTable, afterProj);
                break;
            case DELETE:
                writeDelete(conn, targetDb, targetTable, projectByFields(change.before, tc));
                break;
            default:
                break;
        }
    }

    /** UPDATE 事件中目标主键值是否发生变更（投影后按目标列名比较）。 */
    private boolean isPkChanged(String db, String table, Map<String, Object> before, Map<String, Object> after) throws Exception {
        if (before == null || after == null) {
            return false;
        }
        List<String> pks = primaryKeysOf(db, table);
        if (pks.isEmpty()) {
            return false;
        }
        for (String pk : pks) {
            Object b = before.get(pk);
            Object a = after.get(pk);
            if (b == null ? a != null : !b.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按字段映射投影一行数据：tc.fields 为空 → 原样返回（全列）；
     * 非空 → 只保留 sync==true 的源列，并把 key 由 source 改为 target（与全量/增量一致）。
     * 投影后 key 即目标列名，与 primaryKeysOf 返回的目标主键名一致，写入/删除均按目标列名处理。
     */
    private static Map<String, Object> projectByFields(Map<String, Object> row, TableSyncConfig tc) {
        if (row == null) {
            return null;
        }
        if (tc == null || tc.getFields() == null || tc.getFields().isEmpty()) {
            return row;
        }
        Map<String, String> srcToTgt =
                com.datanote.sync.util.FieldMappingResolver.buildSrcToTgt(tc.getFields());
        Map<String, Object> projected = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : srcToTgt.entrySet()) {
            if (row.containsKey(e.getKey())) {
                projected.put(e.getValue(), row.get(e.getKey()));
            }
        }
        return projected;
    }

    /**
     * c/r/u：按 after 全列 UPSERT（幂等，主键冲突则更新非主键列）。
     * 迭代V3：若 job.markSyncTs==1 且 syncTsField 非空且不在 after 列中，则写列末尾追加该列并绑当前时间。
     */
    private void writeUpsert(Connection conn, String db, String table, Map<String, Object> after) throws Exception {
        if (after == null || after.isEmpty()) {
            log.warn("CDC UPSERT 数据为空，跳过 表={}", table);
            return;
        }
        List<String> pkColumns = primaryKeysOf(db, table);
        List<String> dataColumns = new ArrayList<>(after.keySet());
        boolean appendTs = shouldAppendSyncTs(dataColumns);
        List<String> columns = new ArrayList<>(dataColumns);
        if (appendTs) {
            columns.add(job.getSyncTsField());
        }
        String sql = WriteSqlBuilder.build(targetConnector.getDatabaseType(), "UPSERT", db, table, columns, pkColumns);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < dataColumns.size(); i++) {
                ps.setObject(i + 1, after.get(dataColumns.get(i)));
            }
            // 追加同步时间戳列绑当前时间
            if (appendTs) {
                ps.setObject(dataColumns.size() + 1, new java.sql.Timestamp(System.currentTimeMillis()));
            }
            ps.executeUpdate();
        }
        writeCount.incrementAndGet();
        broadcast("INFO", "CDC 写入 UPSERT " + table + "（" + columns.size() + " 列）");
    }

    /** 迭代V3：是否在写列末尾追加同步时间戳列（job.markSyncTs==1 且 syncTsField 非空且不在已有列中）。 */
    private boolean shouldAppendSyncTs(List<String> dataColumns) {
        Integer mark = job.getMarkSyncTs();
        String field = job.getSyncTsField();
        if (mark == null || mark != 1 || field == null || field.trim().isEmpty()) {
            return false;
        }
        return !dataColumns.contains(field);
    }

    /**
     * d：按主键删除（主键值取自 before）。按 job.deleteMode 选择物理/逻辑删除：
     * <ul>
     *   <li>PHYSICAL（默认）：DELETE FROM target WHERE pk...</li>
     *   <li>LOGICAL：UPDATE target SET {logicalDeleteField}=? WHERE pk...，值为 logicalDeleteValue（默认 '1'）；
     *       logicalDeleteField 为空时记 WARN 并回退物理删除。</li>
     * </ul>
     */
    private void writeDelete(Connection conn, String db, String table, Map<String, Object> before) throws Exception {
        if (before == null || before.isEmpty()) {
            log.warn("CDC DELETE 缺少 before 数据，跳过 表={}", table);
            return;
        }
        List<String> pkColumns = primaryKeysOf(db, table);
        if (pkColumns.isEmpty()) {
            // 无主键无法定位待删行：抛出而非静默跳过，由 handleBatch 回滚并阻止 offset 推进，
            // 避免“源已删、目标仍存”的脏数据（静默跳过会让该删除事件随 offset 永久丢失）
            log.error("CDC DELETE 目标表无主键，拒绝删除以防脏数据 表={}", table);
            throw new IllegalStateException("CDC DELETE 目标表缺少主键，无法定位删除行，表=" + table);
        }
        boolean logical = "LOGICAL".equalsIgnoreCase(job.getDeleteMode());
        String logicalField = job.getLogicalDeleteField();
        // 逻辑删除却漏配标记列：快速失败而非静默物理删除，避免与用户意图相反误删数据
        if (logical && (logicalField == null || logicalField.trim().isEmpty())) {
            throw new IllegalStateException("CDC 逻辑删除未配置 logicalDeleteField，拒绝回退物理删除，表=" + table);
        }

        if (logical) {
            String logicalValue = job.getLogicalDeleteValue() == null ? "1" : job.getLogicalDeleteValue();
            String sql = buildLogicalDeleteSql(db, table, logicalField, pkColumns);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // SET {field}=? 在前，WHERE pk=? 在后
                ps.setObject(1, logicalValue);
                for (int i = 0; i < pkColumns.size(); i++) {
                    ps.setObject(i + 2, before.get(pkColumns.get(i)));
                }
                ps.executeUpdate();
            }
            writeCount.incrementAndGet();
            broadcast("INFO", "CDC 逻辑删除 " + table + "（" + logicalField + "=" + logicalValue + "）");
        } else {
            String sql = buildDeleteSql(db, table, pkColumns);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < pkColumns.size(); i++) {
                    ps.setObject(i + 1, before.get(pkColumns.get(i)));
                }
                ps.executeUpdate();
            }
            writeCount.incrementAndGet();
            broadcast("INFO", "CDC 写入 DELETE " + table);
        }
    }

    /** 取目标表主键（缓存）。key 用 db.table，避免多库同名表取错主键。 */
    private List<String> primaryKeysOf(String db, String table) throws Exception {
        String cacheKey = db + "." + table;
        List<String> cached = pkCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        TableMeta meta = targetConnector.getTableMeta(db, table);
        List<String> pks = meta.getPrimaryKeys();
        if (pks.isEmpty()) {
            log.warn("CDC 目标表无主键（UPSERT 退化为 INSERT IGNORE）: {}.{}", db, table);
        }
        pkCache.put(cacheKey, pks);
        return pks;
    }

    /** 目标表列集合（缓存，DDL 漂移检测用；ADD COLUMN 后失效）。 */
    private java.util.Set<String> targetColumnsOf(String db, String table) throws Exception {
        String key = db + "." + table;
        java.util.Set<String> c = targetColsCache.get(key);
        if (c != null) {
            return c;
        }
        TableMeta meta = targetConnector.getTableMeta(db, table);
        java.util.Set<String> cols = new java.util.LinkedHashSet<>(meta.getColumns());
        targetColsCache.put(key, cols);
        return cols;
    }

    /**
     * DDL 漂移同步（默认关）：after 出现目标表没有的列时，查源列类型并 ALTER 目标 ADD COLUMN。
     * 仅 job.ddlSyncEnabled==1 时生效；关时第一行直接 return（写路径零开销零行为变化）。
     * ALTER 失败上抛（由 handleBatch 回滚重试，避免列加不上导致后续 INSERT 列不匹配静默失败）。
     */
    private void syncDriftColumns(Connection conn, String db, String table, String sourceTable,
                                  Map<String, Object> after) throws Exception {
        if (job.getDdlSyncEnabled() == null || job.getDdlSyncEnabled() != 1 || after == null) {
            return;
        }
        java.util.Set<String> existing = targetColumnsOf(db, table);
        for (String col : after.keySet()) {
            if (!existing.contains(col)) {
                String colType = sourceColumnType(sourceTable, col);
                String mapped = mapDriftColumnType(colType);
                String ddl = "ALTER TABLE " + com.datanote.sync.util.SqlIdentifiers.quote(db)
                        + "." + com.datanote.sync.util.SqlIdentifiers.quote(table)
                        + " ADD COLUMN " + com.datanote.sync.util.SqlIdentifiers.quote(col) + " " + mapped + " NULL";
                try (PreparedStatement ps = conn.prepareStatement(ddl)) {
                    ps.executeUpdate();
                }
                broadcast("WARN", "DDL同步：目标表 " + table + " 新增列 " + col + " " + mapped);
                targetColsCache.remove(db + "." + table); // 失效缓存，下次重查
            }
        }
    }

    /** 漂移列类型映射：目标 MySQL 原样用源 COLUMN_TYPE；Doris/StarRocks 走 TypeMappingService。 */
    private String mapDriftColumnType(String colType) {
        String type = targetConnector.getDatabaseType();
        if ("MYSQL".equalsIgnoreCase(type)) {
            return colType;
        }
        return new com.datanote.sync.schema.TypeMappingService().mysqlToDoris(colType);
    }

    /** 查源表某列的 COLUMN_TYPE（用源连接）。查不到给 TEXT 宽松兜底（不抛）。 */
    private String sourceColumnType(String sourceTable, String col) {
        try (Connection c = connectionManager.getConnection(job.getSourceDsId(), job.getSourceDb())) {
            String sql = "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, job.getSourceDb());
                ps.setString(2, sourceTable);
                ps.setString(3, col);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (Exception ignore) {
            // 查不到/查询异常：兜底 TEXT，不阻塞写入
        }
        return "TEXT";
    }

    /** 优雅关闭：close 引擎 + 关线程池。 */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        broadcast("INFO", "CDC 引擎停止 jobId=" + job.getId());
        try {
            if (engine != null) {
                engine.close();
            }
        } catch (Exception e) {
            log.error("关闭 Debezium 引擎失败 jobId={}", job.getId(), e);
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void broadcast(String level, String msg) {
        appendLog(level, msg);
        try {
            logBroadcastService.broadcastTaskLog(job.getId(), "DbSync", level, msg);
        } catch (Exception ignore) {
            // 广播失败不影响主流程
        }
    }

    /** 追加到持久化日志缓冲（带上限，超出从头部裁剪）。 */
    private void appendLog(String level, String msg) {
        synchronized (logLock) {
            logBuffer.append('[').append(level).append("] ").append(msg).append('\n');
            if (logBuffer.length() > MAX_LOG_BUFFER) {
                logBuffer.delete(0, logBuffer.length() - MAX_LOG_BUFFER);
            }
        }
    }

    /** 累计读取（已应用的变更事件数）。 */
    public long getReadCount() {
        return readCount.get();
    }

    /** 累计写入（成功的目标库写/删次数）。 */
    public long getWriteCount() {
        return writeCount.get();
    }

    /** 累计错误（处理失败的事件数）。 */
    public long getErrorCount() {
        return errorCount.get();
    }

    /**
     * 读取 Debezium 流式阶段落后源库的毫秒数（真实 CDC 延迟），来自引擎注册到 JVM 平台 MBeanServer 的指标。
     * 1.9.x 的 ObjectName 用 database.server.name（即 datanote_cdc_&lt;jobId&gt;）。读不到返回 null（降级不展示）。
     */
    public Long getStreamingLagMs() {
        try {
            javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                    "debezium.mysql:type=connector-metrics,context=streaming,server=datanote_cdc_" + job.getId());
            Object v = mbs.getAttribute(on, "MilliSecondsBehindSource");
            return v == null ? null : ((Number) v).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** 累计已处理（snapshot+streaming）事件总数，来自 streaming 上下文 MBean。读不到返回 null。 */
    public Long getEventsSeen() { return readMbeanNumber("streaming", "TotalNumberOfEventsSeen"); }
    /** 全量快照是否已完成（snapshot 上下文 MBean）。读不到返回 null。 */
    public Boolean getSnapshotCompleted() { return readSnapshotBool("SnapshotCompleted"); }
    /** 全量快照是否正在进行（snapshot 上下文 MBean）。读不到返回 null。 */
    public Boolean getSnapshotRunning() { return readSnapshotBool("SnapshotRunning"); }
    private Long readMbeanNumber(String context, String attr) {
        try {
            javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                "debezium.mysql:type=connector-metrics,context=" + context + ",server=datanote_cdc_" + job.getId());
            Object v = mbs.getAttribute(on, attr);
            return v == null ? null : ((Number) v).longValue();
        } catch (Exception e) { return null; }
    }
    private Boolean readSnapshotBool(String attr) {
        try {
            javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                "debezium.mysql:type=connector-metrics,context=snapshot,server=datanote_cdc_" + job.getId());
            Object v = mbs.getAttribute(on, attr);
            return v == null ? null : (Boolean) v;
        } catch (Exception e) { return null; }
    }

    /** 当前日志缓冲快照（供执行记录落库回放）。 */
    public String getLogSnapshot() {
        synchronized (logLock) {
            return logBuffer.toString();
        }
    }

    /** 坏事件落库到死信表（deadLetterMapper 为 null 或落库异常均静默忽略，不影响主流程）。 */
    private void saveDeadLetter(String db, String table, String originValue, String reason, String type) {
        if (deadLetterMapper == null) return;
        try {
            com.datanote.model.DnCdcDeadLetter dl = new com.datanote.model.DnCdcDeadLetter();
            dl.setJobId(job.getId());
            dl.setSourceDb(db); dl.setSourceTable(table);
            dl.setOriginValue(abbreviate(originValue));
            dl.setErrorReason(reason); dl.setErrorType(type);
            deadLetterMapper.insert(dl);
        } catch (Exception ignore) {}
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    // ===== 纯逻辑：Debezium JSON -> 变更操作（可单测） =====

    /** 变更操作类型。 */
    public enum OpType {
        INSERT, UPDATE, DELETE
    }

    /** 解析后的变更操作（不含目标表路由，目标表由调用方按 sourceTable 查配置）。 */
    public static final class ChangeOp {
        public final OpType opType;
        public final String sourceDb;
        public final String sourceTable;
        public final Map<String, Object> before;
        public final Map<String, Object> after;

        public ChangeOp(OpType opType, String sourceDb, String sourceTable,
                        Map<String, Object> before, Map<String, Object> after) {
            this.opType = opType;
            this.sourceDb = sourceDb;
            this.sourceTable = sourceTable;
            this.before = before;
            this.after = after;
        }
    }

    /**
     * 解析 Debezium JSON 字符串为变更操作。
     *
     * <p>结构：{@code {"payload": {"op": "c|r|u|d", "before": {...}, "after": {...},
     * "source": {"db": "...", "table": "..."}}}}。op 为 null/不支持（如 truncate "t"、message "m"）
     * 时返回 null（调用方跳过）。
     *
     * @param json Debezium 值 JSON（record.value()）
     * @return 变更操作；无法解析为支持的操作时返回 null
     */
    public static ChangeOp parseChange(String json) {
        JSONObject root = JSON.parseObject(json);
        if (root == null) {
            return null;
        }
        // 含 schema 的 envelope：真正数据在 payload 下；无 schema 时整体即 payload
        JSONObject payload = root.containsKey("payload") ? root.getJSONObject("payload") : root;
        if (payload == null) {
            return null;
        }
        String op = payload.getString("op");
        OpType opType = mapOp(op);
        if (opType == null) {
            return null;
        }
        JSONObject source = payload.getJSONObject("source");
        String db = source == null ? null : source.getString("db");
        String table = source == null ? null : source.getString("table");
        Map<String, Object> before = toMap(payload.getJSONObject("before"));
        Map<String, Object> after = toMap(payload.getJSONObject("after"));
        return new ChangeOp(opType, db, table, before, after);
    }

    /** Debezium op 字符 -> 操作类型；c(create)/r(read,快照)->INSERT，u->UPDATE，d->DELETE；其余 null。 */
    private static OpType mapOp(String op) {
        if (op == null) {
            return null;
        }
        switch (op) {
            case "c":
            case "r":
                return OpType.INSERT;
            case "u":
                return OpType.UPDATE;
            case "d":
                return OpType.DELETE;
            default:
                return null;
        }
    }

    /** JSONObject -> 普通 Map（保持插入顺序，null 入参返回 null）。 */
    private static Map<String, Object> toMap(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        return new java.util.LinkedHashMap<>(obj);
    }

    /** 按主键构建 DELETE SQL（库表与列名经 SqlIdentifiers 校验+反引号）。 */
    static String buildDeleteSql(String db, String table, List<String> pkColumns) {
        String fullTable = com.datanote.sync.util.SqlIdentifiers.quote(db)
                + "." + com.datanote.sync.util.SqlIdentifiers.quote(table);
        String where = pkColumns.stream()
                .map(c -> com.datanote.sync.util.SqlIdentifiers.quote(c) + " = ?")
                .collect(Collectors.joining(" AND "));
        return "DELETE FROM " + fullTable + " WHERE " + where;
    }

    /**
     * 按主键构建逻辑删除 UPDATE SQL：{@code UPDATE db.table SET `field` = ? WHERE pk = ? [AND ...]}。
     * 库表/列名经 SqlIdentifiers 校验+反引号；标记值与主键值均为 PreparedStatement 参数（占位 ?）。
     * 参数顺序：第 1 个为标记值，其后依次为各主键值。
     */
    static String buildLogicalDeleteSql(String db, String table, String logicalField, List<String> pkColumns) {
        String fullTable = com.datanote.sync.util.SqlIdentifiers.quote(db)
                + "." + com.datanote.sync.util.SqlIdentifiers.quote(table);
        String set = com.datanote.sync.util.SqlIdentifiers.quote(logicalField) + " = ?";
        String where = pkColumns.stream()
                .map(c -> com.datanote.sync.util.SqlIdentifiers.quote(c) + " = ?")
                .collect(Collectors.joining(" AND "));
        return "UPDATE " + fullTable + " SET " + set + " WHERE " + where;
    }
}
