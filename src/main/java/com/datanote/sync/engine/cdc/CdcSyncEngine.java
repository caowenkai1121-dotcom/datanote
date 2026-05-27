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
    private final com.datanote.service.LogBroadcastService logBroadcastService;
    private final String cryptoKey;
    /** database.server.id 基准值（可配置，避免与生产 slave 撞号）。 */
    private final long serverIdBase;

    /** 源表名 -> 该表同步配置（含目标表名）。 */
    private final Map<String, TableSyncConfig> tableMap = new ConcurrentHashMap<>();
    /** "目标库.目标表" -> 主键列（缓存，避免每条事件查 information_schema；带库名前缀防多库同名表取错）。 */
    private final Map<String, List<String>> pkCache = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile DebeziumEngine<ChangeEvent<String, String>> engine;
    private volatile ExecutorService executor;
    /** 目标库连接器（按目标库取主键元数据用，MySQL 协议族）。 */
    private MysqlConnector targetConnector;

    public CdcSyncEngine(DnSyncJob job,
                         DnDatasource sourceDs,
                         List<TableSyncConfig> tables,
                         ConnectionManager connectionManager,
                         com.datanote.service.LogBroadcastService logBroadcastService,
                         String cryptoKey,
                         long serverIdBase) {
        this.job = job;
        this.sourceDs = sourceDs;
        this.tables = tables;
        this.connectionManager = connectionManager;
        this.logBroadcastService = logBroadcastService;
        this.cryptoKey = cryptoKey;
        this.serverIdBase = serverIdBase;
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
        // fat-jar 下 Debezium 用 Class.forName 加载连接器/存储类，需把上下文类加载器设为本类加载器
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        this.targetConnector = new MysqlConnector(connectionManager, job.getTargetDsId(), job.getTargetDb(), "MYSQL");

        Properties props = buildProps();
        Long jobId = job.getId();
        this.engine = DebeziumEngine.create(Json.class)
                .using(props)
                .using(getClass().getClassLoader())
                .notifying(this::handleEvent)
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
        // server.id 需在源库唯一（同一源库多任务避免撞号）；base 可配置 + 取模避免溢出/撞生产 slave
        long serverId = serverIdBase + (jobId % 100000L);
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
        // 删除事件保留有效记录（不产生 tombstone null 记录，简化下游处理）
        props.setProperty("tombstones.on.delete", "false");
        return props;
    }

    /** 源库.各源表，逗号分隔（table.include.list 需库名前缀）。 */
    private String buildTableIncludeList() {
        return tableMap.keySet().stream()
                .map(t -> job.getSourceDb() + "." + t)
                .collect(Collectors.joining(","));
    }

    /**
     * 处理单条 Debezium 变更事件。record.value() 是 Debezium JSON 字符串。
     * 解析失败/无关表只记日志跳过，不抛异常（避免引擎反复重试卡死）。
     */
    private void handleEvent(ChangeEvent<String, String> record) {
        String value = record.value();
        if (value == null || value.isEmpty()) {
            return; // tombstone 等空记录
        }
        try {
            ChangeOp change = parseChange(value);
            if (change == null) {
                return;
            }
            TableSyncConfig tc = tableMap.get(change.sourceTable);
            if (tc == null) {
                log.debug("CDC 事件命中未配置的源表，跳过: {}", change.sourceTable);
                return;
            }
            applyChange(tc, change);
        } catch (Exception e) {
            log.error("CDC 事件处理失败 jobId={} value={}", job.getId(), abbreviate(value), e);
            broadcast("ERROR", "CDC 事件处理失败: " + e.getMessage());
        }
    }

    /** 把一条变更写入目标库。若该表配置了字段映射，先按 source->target 投影 after/before。 */
    private void applyChange(TableSyncConfig tc, ChangeOp change) throws Exception {
        String targetTable = tc.getTargetTable();
        String targetDb = job.getTargetDb();
        switch (change.opType) {
            case INSERT:
                writeUpsert(targetDb, targetTable, projectByFields(change.after, tc));
                break;
            case UPDATE:
                // 用 after 全量 UPSERT 即可覆盖更新（幂等）
                writeUpsert(targetDb, targetTable, projectByFields(change.after, tc));
                break;
            case DELETE:
                writeDelete(targetDb, targetTable, projectByFields(change.before, tc));
                break;
            default:
                break;
        }
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

    /** c/r/u：按 after 全列 UPSERT（幂等，主键冲突则更新非主键列）。 */
    private void writeUpsert(String db, String table, Map<String, Object> after) throws Exception {
        if (after == null || after.isEmpty()) {
            log.warn("CDC UPSERT 数据为空，跳过 表={}", table);
            return;
        }
        List<String> pkColumns = primaryKeysOf(db, table);
        List<String> columns = new ArrayList<>(after.keySet());
        String sql = WriteSqlBuilder.build("UPSERT", db, table, columns, pkColumns);
        try (Connection conn = connectionManager.getConnection(job.getTargetDsId(), db);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < columns.size(); i++) {
                ps.setObject(i + 1, after.get(columns.get(i)));
            }
            ps.executeUpdate();
        }
        broadcast("INFO", "CDC 写入 UPSERT " + table + "（" + columns.size() + " 列）");
    }

    /** d：按主键 DELETE（主键值取自 before）。 */
    private void writeDelete(String db, String table, Map<String, Object> before) throws Exception {
        if (before == null || before.isEmpty()) {
            log.warn("CDC DELETE 缺少 before 数据，跳过 表={}", table);
            return;
        }
        List<String> pkColumns = primaryKeysOf(db, table);
        if (pkColumns.isEmpty()) {
            log.warn("CDC DELETE 目标表无主键，跳过 表={}", table);
            return;
        }
        String sql = buildDeleteSql(db, table, pkColumns);
        try (Connection conn = connectionManager.getConnection(job.getTargetDsId(), db);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < pkColumns.size(); i++) {
                ps.setObject(i + 1, before.get(pkColumns.get(i)));
            }
            ps.executeUpdate();
        }
        broadcast("INFO", "CDC 写入 DELETE " + table);
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
        try {
            logBroadcastService.broadcastTaskLog(job.getId(), "DbSync", level, msg);
        } catch (Exception ignore) {
            // 广播失败不影响主流程
        }
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
}
