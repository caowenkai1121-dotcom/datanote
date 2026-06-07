package com.datanote.domain.datasource;

import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.domain.metadata.DataMapService;
import com.datanote.platform.config.HiveConfig;
import com.datanote.domain.metadata.model.ColumnInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 数据源在线探查服务 —— 直连数仓(Doris)做实时元数据探查：库/表/列、预览、探查、DDL、分区、表信息。
 *
 * 重构来源：自原 DataMapService(648 行 god-service)拆出"在线探查"职责，
 * 使"连库探查"与"读离线目录(DnTableMeta/收藏/评论)"彻底分离——本服务是唯一直连库探查出口。
 * 方法逻辑逐字迁移，行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceExploreService {

    private final HiveConfig hiveConfig;

    /** 排除的 Hive 系统库 */
    private static final Set<String> SYS_DBS = new HashSet<String>(Arrays.asList(
            "default", "information_schema", "sys"
    ));

    // ========== 库/表/列 ==========

    /** 获取数据库列表（排除系统库） */
    public List<String> getHiveDatabases() throws SQLException {
        List<String> dbs = new ArrayList<String>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                String db = rs.getString(1);
                if (!SYS_DBS.contains(db)) {
                    dbs.add(db);
                }
            }
        }
        return dbs;
    }

    /** 获取指定库的表列表 */
    public List<String> getHiveTables(String db) throws SQLException {
        if (db == null || !db.matches("[a-zA-Z0-9_]+")) { // 底层防注入:库名拼入SHOW TABLES IN
            throw new IllegalArgumentException("非法的库名");
        }
        List<String> tables = new ArrayList<String>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW TABLES IN " + db)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /** 获取 Doris 指定表的字段信息。 */
    public List<ColumnInfo> getHiveColumns(String db, String table) throws SQLException {
        if (db == null || !db.matches("[a-zA-Z0-9_]+") || table == null || !table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法的库名或表名"); // 底层防注入:拼入DESCRIBE/information_schema
        }
        try {
            return getDorisColumns(db, table);
        } catch (SQLException e) {
            log.warn("通过 information_schema 获取字段失败，降级 DESCRIBE: {}.{}, {}", db, table, e.getMessage());
            return getColumnsByDescribe(db, table);
        }
    }

    private List<ColumnInfo> getDorisColumns(String db, String table) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (Connection conn = hiveConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, EXTRA, COLUMN_COMMENT "
                             + "FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                             + "ORDER BY ORDINAL_POSITION")) {
            stmt.setString(1, db);
            stmt.setString(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setType(nullToDefault(rs.getString("COLUMN_TYPE"), "string"));
                    col.setComment(nullToDefault(rs.getString("COLUMN_COMMENT"), ""));
                    col.setHiveType(col.getType());
                    col.setKey(nullToDefault(rs.getString("COLUMN_KEY"), ""));
                    col.setNullable(nullToDefault(rs.getString("IS_NULLABLE"), "YES"));
                    col.setExtra(nullToDefault(rs.getString("EXTRA"), ""));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    private List<ColumnInfo> getColumnsByDescribe(String db, String table) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("DESCRIBE " + db + "." + table)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String colName = rs.getString(1);
                if (colName == null || colName.trim().isEmpty()) break;
                // DESCRIBE 输出中分区信息以 # 开头，跳过
                if (colName.trim().startsWith("#")) break;
                ColumnInfo col = new ColumnInfo();
                col.setName(colName.trim());
                col.setType(rs.getString(2) != null ? rs.getString(2).trim() : "string");
                col.setComment(columnCount >= 7 && rs.getString(7) != null ? rs.getString(7).trim() : "");
                col.setHiveType(col.getType());
                col.setKey(columnCount >= 4 && rs.getString(4) != null ? rs.getString(4).trim() : "");
                col.setNullable(columnCount >= 3 && rs.getString(3) != null ? rs.getString(3).trim() : "YES");
                col.setExtra(columnCount >= 6 && rs.getString(6) != null ? rs.getString(6).trim() : "");
                columns.add(col);
            }
        }
        return columns;
    }

    // ========== 全表摘要（供搜索/AI搜索/热门 复用） ==========

    public List<Map<String, Object>> getAllTablesSummary() throws SQLException {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<String> dbs = getHiveDatabases();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String db : dbs) {
                try (ResultSet rs = stmt.executeQuery("SHOW TABLES IN " + db)) {
                    while (rs.next()) {
                        String tableName = rs.getString(1);
                        Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("TABLE_SCHEMA", db);
                        row.put("TABLE_NAME", tableName);
                        row.put("TABLE_COMMENT", "");
                        row.put("TABLE_ROWS", null);
                        row.put("col_count", null);
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    // ========== 预览 ==========

    public Map<String, Object> preview(String db, String table) throws SQLException {
        if (db == null || !db.matches("[a-zA-Z0-9_]+") || table == null || !table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法的库名或表名"); // 底层防注入
        }
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + com.datanote.domain.integration.util.DorisSqlUtil.quoteQualified(db, table) + " LIMIT 20")) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> headers = new ArrayList<String>();
            for (int i = 1; i <= colCount; i++) {
                String colName = meta.getColumnName(i);
                // Hive 返回的列名可能带 db.table 前缀，去掉
                if (colName.contains(".")) {
                    colName = colName.substring(colName.lastIndexOf('.') + 1);
                }
                headers.add(colName);
            }
            List<List<String>> rows = new ArrayList<List<String>>();
            while (rs.next()) {
                List<String> row = new ArrayList<String>();
                for (int i = 1; i <= colCount; i++) {
                    String val = rs.getString(i);
                    row.add(val != null ? val : "NULL");
                }
                rows.add(row);
            }
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("headers", headers);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            return result;
        }
    }

    // ========== 探查 ==========

    public Map<String, Object> profile(String db, String table) throws SQLException {
        List<ColumnInfo> columns = getHiveColumns(db, table);
        List<Map<String, Object>> fieldStats = new ArrayList<Map<String, Object>>();

        // Hive 聚合查询较慢，先查总行数
        long totalRows = 0;
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + com.datanote.domain.integration.util.DorisSqlUtil.quoteQualified(db, table))) {
            if (rs.next()) totalRows = rs.getLong(1);
        }

        // 逐字段统计（限制字段数，避免 Hive 查询过慢）
        int maxProfileFields = Math.min(columns.size(), 30);
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = 0; i < maxProfileFields; i++) {
                ColumnInfo col = columns.get(i);
                Map<String, Object> stat = new HashMap<String, Object>();
                stat.put("name", col.getName());
                stat.put("type", col.getType());
                stat.put("comment", col.getComment());
                stat.put("key", "");
                stat.put("nullable", "YES");
                try {
                    String colName = "`" + col.getName() + "`";
                    String sql = "SELECT COUNT(*) AS total, "
                            + "SUM(CASE WHEN " + colName + " IS NULL THEN 1 ELSE 0 END) AS null_count, "
                            + "COUNT(DISTINCT " + colName + ") AS distinct_count "
                            + "FROM " + com.datanote.domain.integration.util.DorisSqlUtil.quoteQualified(db, table);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) {
                            long nullCount = rs.getLong("null_count");
                            stat.put("nullCount", nullCount);
                            stat.put("nullRate", totalRows > 0 ? String.format("%.1f%%", nullCount * 100.0 / totalRows) : "0%");
                            stat.put("distinctCount", rs.getLong("distinct_count"));
                        }
                    }
                } catch (SQLException e) {
                    stat.put("error", e.getMessage());
                }
                fieldStats.add(stat);
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("totalRows", totalRows);
        result.put("columnCount", columns.size());
        result.put("fields", fieldStats);
        return result;
    }

    // ========== DDL / SQL 生成 ==========

    public Map<String, String> generateDdlAndSelect(String db, String table) throws SQLException {
        if (db == null || !db.matches("[a-zA-Z0-9_]+") || table == null || !table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法的库名或表名"); // 底层防注入
        }
        Map<String, String> result = new HashMap<String, String>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + com.datanote.domain.integration.util.DorisSqlUtil.quoteQualified(db, table))) {
            StringBuilder ddl = new StringBuilder();
            while (rs.next()) {
                ddl.append(rs.getString(1)).append("\n");
            }
            result.put("ddl", ddl.toString().trim());
        }

        List<ColumnInfo> columns = getHiveColumns(db, table);
        StringBuilder selectSql = new StringBuilder("SELECT\n");
        for (int i = 0; i < columns.size(); i++) {
            selectSql.append("  ").append(columns.get(i).getName());
            if (i < columns.size() - 1) selectSql.append(",");
            String comment = columns.get(i).getComment();
            if (comment != null && !comment.isEmpty()) {
                selectSql.append("  -- ").append(comment);
            }
            selectSql.append("\n");
        }
        selectSql.append("FROM ").append(db).append(".").append(table).append("\nLIMIT 100;");
        result.put("selectSql", selectSql.toString());
        return result;
    }

    // ========== 表基本信息（information_schema.TABLES） ==========

    public Map<String, Object> getDorisTableInfo(String db, String table) throws SQLException {
        Map<String, Object> info = new HashMap<String, Object>();
        info.put("db", db);
        info.put("table", table);
        info.put("comment", "");
        info.put("engine", "Doris");

        try (Connection conn = hiveConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS, ENGINE, CREATE_TIME, UPDATE_TIME, DATA_LENGTH "
                             + "FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            stmt.setString(1, db);
            stmt.setString(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return info;
                }
                putIfNotBlank(info, "table", rs.getString("TABLE_NAME"));
                putIfNotBlank(info, "comment", rs.getString("TABLE_COMMENT"));
                putIfNotBlank(info, "engine", rs.getString("ENGINE"));
                putObjectString(info, "createTime", rs.getObject("CREATE_TIME"));
                putObjectString(info, "updateTime", rs.getObject("UPDATE_TIME"));
                putLong(info, "rowCount", rs.getObject("TABLE_ROWS"));
                putLong(info, "dataLength", rs.getObject("DATA_LENGTH"));
            }
        }
        return info;
    }

    // ========== 分区信息 ==========

    /**
     * 获取 Doris 表的分区列表。DataNote 建的 ODS 表多为非分区表，
     * Doris 非分区表执行 SHOW PARTITIONS 会报错，此处捕获并返回空列表。
     */
    public List<Map<String, Object>> getPartitions(String db, String table) throws SQLException {
        if (db == null || !db.matches("[a-zA-Z0-9_]+") || table == null || !table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法的库名或表名"); // 底层防注入
        }
        List<Map<String, Object>> partitions = new ArrayList<Map<String, Object>>();
        String sql = "SHOW PARTITIONS FROM `" + db + "`.`" + table + "`";
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> raw = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= cols; i++) {
                    raw.put(md.getColumnLabel(i), rs.getObject(i));
                }
                partitions.add(mapDorisPartitionRow(raw));
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            // 非分区表：Doris 提示 "is not a partitioned table" / "unpartitioned table"
            if (msg.contains("not a partitioned") || msg.contains("not partitioned")
                    || msg.contains("unpartitioned")) {
                return partitions;
            }
            throw e;
        }
        return partitions;
    }

    /**
     * 把 Doris SHOW PARTITIONS 的一行（列名→值）映射为前端使用的分区信息。
     * 抽成静态纯函数便于单元测试。
     */
    static Map<String, Object> mapDorisPartitionRow(Map<String, Object> raw) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("partition", str(raw.get("PartitionName")));
        p.put("partitionKey", str(raw.get("PartitionKey")));
        p.put("range", str(raw.get("Range")));
        p.put("buckets", raw.get("Buckets"));
        p.put("state", str(raw.get("State")));
        // Doris 的 DataSize 已是可读字符串（如 "1.234 GB"）
        p.put("totalSizeDisplay", str(raw.get("DataSize")));
        p.put("lastModified", str(raw.get("VisibleVersionTime")));
        return p;
    }

    // ========== 内部助手 ==========

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private String nullToDefault(String value, String defaultValue) {
        return value != null ? value.trim() : defaultValue;
    }

    private void putIfNotBlank(Map<String, Object> info, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            info.put(key, value.trim());
        }
    }

    private void putObjectString(Map<String, Object> info, String key, Object value) {
        if (value != null && !value.toString().trim().isEmpty()) {
            info.put(key, value.toString());
        }
    }

    private void putLong(Map<String, Object> info, String key, Object value) {
        Long parsed = toLong(value);
        if (parsed != null) {
            info.put(key, parsed);
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
