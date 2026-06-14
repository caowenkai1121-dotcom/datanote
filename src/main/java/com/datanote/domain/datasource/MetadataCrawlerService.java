package com.datanote.domain.datasource;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.config.HiveConfig;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.metadata.mapper.DnMetaCollectLogMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.metadata.model.DnMetaCollectLog;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据自动采集服务 — 从 MySQL 源库 / Doris 数仓 读取 information_schema，增量 upsert 元数据。
 * 技术字段覆盖更新，业务字段"空才填、非空保留"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataCrawlerService {

    private final DnDatasourceMapper datasourceMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnMetaCollectLogMapper collectLogMapper;
    private final HiveConfig hiveConfig;
    private final com.datanote.platform.ai.graph.GraphMirrorService graphMirrorService;
    private final com.datanote.platform.ai.vector.VectorIndexService vectorIndexService;
    private final DatasourceExploreService datasourceExploreService;   // 采集后失效数据地图全表摘要缓存

    /** 全量采集单飞守卫: 手动触发(/crawl/all 裸线程)与每日定时可能并发, 防两次全量采集互相打架。 */
    private final java.util.concurrent.atomic.AtomicBoolean crawlingAll = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    private static final String SQL_SCHEMATA =
            "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
            + "WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys') "
            + "ORDER BY SCHEMA_NAME";
    private static final String SQL_TABLES =
            "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS, DATA_LENGTH, TABLE_TYPE "
            + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    private static final String SQL_COLUMNS =
            "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, COLUMN_KEY, IS_NULLABLE, ORDINAL_POSITION "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";

    private static final long WAREHOUSE_DS_ID = 0L;

    // ========== 对外采集入口 ==========

    /** 采集全部：所有源数据源 + Doris 数仓。单飞: 已在采集中则跳过本次, 防手动与定时并发重复全量采集。 */
    public void crawlAll() {
        if (!crawlingAll.compareAndSet(false, true)) {
            log.warn("元数据全量采集已在进行中, 跳过本次触发");
            return;
        }
        try {
            List<DnDatasource> all = datasourceMapper.selectList(null);
            if (all != null) {
                for (DnDatasource ds : all) {
                    if (ds == null || ds.getId() == null) {
                        continue;   // 脏数据(空记录/缺主键)跳过,无法定位采集
                    }
                    try {
                        crawlDatasource(ds.getId());
                    } catch (Exception e) {
                        log.error("采集数据源失败 dsId={}", ds.getId(), e);
                    }
                }
            }
            try {
                crawlWarehouse();
            } catch (Exception e) {
                log.error("采集 Doris 数仓失败", e);
            }
        } finally {
            crawlingAll.set(false);
        }
    }

    /** 采集指定 MySQL 源数据源（全部非系统库） */
    public DnMetaCollectLog crawlDatasource(Long datasourceId) {
        if (datasourceId == null) {
            throw new BusinessException("数据源 ID 不能为空");
        }
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new BusinessException("数据源不存在: " + datasourceId);
        }
        String password = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        String url = "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort()
                + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
        return runCollect(datasourceId, "MYSQL", "all", () ->
                DriverManager.getConnection(url, ds.getUsername(), password));
    }

    /** 采集 Doris 数仓（datasourceId=0，连接池） */
    public DnMetaCollectLog crawlWarehouse() {
        return runCollect(WAREHOUSE_DS_ID, "DORIS", "all", hiveConfig::getConnection);
    }

    // ========== 采集核心 ==========

    @FunctionalInterface
    interface ConnSupplier { Connection get() throws SQLException; }

    private DnMetaCollectLog runCollect(Long datasourceId, String dbType, String scope, ConnSupplier supplier) {
        DnMetaCollectLog logRec = new DnMetaCollectLog();
        logRec.setDatasourceId(datasourceId);
        logRec.setDbType(dbType);
        logRec.setScope(scope);
        logRec.setStartedAt(LocalDateTime.now());
        long start = System.currentTimeMillis();
        int tableCount = 0;
        int columnCount = 0;
        try (Connection conn = supplier.get()) {
            List<String> dbs = listSchemas(conn);
            for (String db : dbs) {
                List<String[]> tables = listTables(conn, db);
                for (String[] t : tables) {
                    Long tableMetaId = upsertTable(datasourceId, dbType, db, t);
                    tableCount++;
                    columnCount += upsertColumns(conn, db, t[0], tableMetaId);
                }
            }
            logRec.setStatus("success");
            datasourceExploreService.evictTablesSummaryCache();   // 采集成功→失效搜索缓存, 新表立即可见
        } catch (Exception e) {
            logRec.setStatus("error");
            logRec.setMessage(e.getMessage());
            log.error("元数据采集失败 dsId={} dbType={}", datasourceId, dbType, e);
        }
        logRec.setTableCount(tableCount);
        logRec.setColumnCount(columnCount);
        logRec.setDurationMs(System.currentTimeMillis() - start);
        logRec.setFinishedAt(LocalDateTime.now());
        collectLogMapper.insert(logRec);
        return logRec;
    }

    private List<String> listSchemas(Connection conn) throws SQLException {
        List<String> dbs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL_SCHEMATA)) {
            while (rs.next()) dbs.add(rs.getString(1));
        }
        return dbs;
    }

    /** 返回每张表的 [name, comment, rows, dataLength, tableType] */
    private List<String[]> listTables(Connection conn, String db) throws SQLException {
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_TABLES)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[]{
                            rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_COMMENT"),
                            rs.getObject("TABLE_ROWS") == null ? null : String.valueOf(rs.getLong("TABLE_ROWS")),
                            rs.getObject("DATA_LENGTH") == null ? null : String.valueOf(rs.getLong("DATA_LENGTH")),
                            rs.getString("TABLE_TYPE")
                    });
                }
            }
        }
        return tables;
    }

    private Long upsertTable(Long datasourceId, String dbType, String db, String[] t) {
        DnTableMeta meta = findTable(datasourceId, db, t[0]);
        boolean isNew = meta == null;
        if (isNew) {
            meta = new DnTableMeta();
            meta.setDatasourceId(datasourceId);
            meta.setDatabaseName(db);
            meta.setTableName(t[0]);
            meta.setCreatedAt(LocalDateTime.now());
        }
        mergeTableTechnical(meta, dbType, t[4], parseLong(t[2]), parseLong(t[3]), t[1]);
        meta.setLastCollectedAt(LocalDateTime.now());
        meta.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            tableMetaMapper.insert(meta);
        } else {
            tableMetaMapper.updateById(meta);
        }
        return meta.getId();
    }

    private int upsertColumns(Connection conn, String db, String table, Long tableMetaId) throws SQLException {
        int count = 0;
        // 预载该表已有列到 Map, 循环内查 Map 判存在性, 替代每列一次 findColumn 点查(全量采集主要耗时点)
        Map<String, DnColumnMeta> existingCols = new HashMap<>();
        List<DnColumnMeta> _cols = columnMetaMapper.selectList(new QueryWrapper<DnColumnMeta>().eq("table_meta_id", tableMetaId));
        if (_cols != null) for (DnColumnMeta c : _cols) { // selectList 理论可返回 null
            if (c.getColumnName() != null) existingCols.put(c.getColumnName(), c);
        }
        try (PreparedStatement ps = conn.prepareStatement(SQL_COLUMNS)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    DnColumnMeta col = existingCols.get(colName);
                    boolean isNew = col == null;
                    if (isNew) {
                        col = new DnColumnMeta();
                        col.setTableMetaId(tableMetaId);
                        col.setColumnName(colName);
                        col.setCreatedAt(LocalDateTime.now());
                    }
                    Integer ord = rs.getObject("ORDINAL_POSITION") == null ? null : rs.getInt("ORDINAL_POSITION");
                    mergeColumnTechnical(col, rs.getString("COLUMN_TYPE"), rs.getString("COLUMN_KEY"),
                            rs.getString("IS_NULLABLE"), ord, rs.getString("COLUMN_COMMENT"));
                    col.setLastCollectedAt(LocalDateTime.now());
                    col.setUpdatedAt(LocalDateTime.now());
                    if (isNew) {
                        columnMetaMapper.insert(col);
                    } else {
                        columnMetaMapper.updateById(col);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private DnTableMeta findTable(Long datasourceId, String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
        qw.eq("datasource_id", datasourceId).eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        return tableMetaMapper.selectOne(qw);
    }

    private DnColumnMeta findColumn(Long tableMetaId, String columnName) {
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tableMetaId).eq("column_name", columnName).last("LIMIT 1");
        return columnMetaMapper.selectOne(qw);
    }

    // ========== 纯函数（可单测） ==========

    /** 合并表技术字段：技术字段覆盖，业务描述空才填 */
    static void mergeTableTechnical(DnTableMeta t, String dbType, String tableType,
                                    Long rows, Long dataLength, String dbComment) {
        t.setDbType(dbType);
        t.setTableType(tableType);
        if (rows != null) t.setRowCount(rows);
        t.setSizeBytes(dataLength);
        if (isBlank(t.getTableComment()) && !isBlank(dbComment)) {
            t.setTableComment(dbComment);
        }
    }

    /** 合并字段技术信息：技术字段覆盖，业务描述空才填 */
    static void mergeColumnTechnical(DnColumnMeta c, String dataType, String columnKey,
                                     String nullable, Integer ordinal, String dbComment) {
        c.setDataType(dataType);
        c.setColumnKey(columnKey);
        c.setIsNullable(nullable);
        c.setOrdinal(ordinal);
        if (isBlank(c.getBusinessDesc()) && !isBlank(dbComment)) {
            c.setBusinessDesc(dbComment);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    // ========== 定时采集 ==========

    /** 每日 01:00 全量采集 */
    @Scheduled(cron = "0 0 1 * * ?")
    public void scheduledCrawl() {
        log.info("启动每日元数据采集");
        crawlAll();
        // 采集后增量同步派生库(各自隔离, 失败不影响采集主流程)
        try { graphMirrorService.fullSync(); } catch (Exception e) { log.warn("采集后图库镜像失败: {}", e.getMessage()); }
        try { vectorIndexService.fullReindex(); } catch (Exception e) { log.warn("采集后向量重建失败: {}", e.getMessage()); }
    }
}
