package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.config.HiveConfig;
import com.datanote.mapper.DnSearchHistoryMapper;
import com.datanote.mapper.DnTableCommentMapper;
import com.datanote.mapper.DnTableFavoriteMapper;
import com.datanote.mapper.DnTableMetaMapper;
import com.datanote.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.datanote.util.ProcessUtil;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 数据地图 Service — 基于 Hive 的元数据搜索、预览、探查、DDL、收藏、评论等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataMapService {

    private final AiAssistService aiAssistService;
    private final HiveConfig hiveConfig;
    private final DnTableCommentMapper tableCommentMapper;
    private final DnTableFavoriteMapper tableFavoriteMapper;
    private final DnSearchHistoryMapper searchHistoryMapper;
    private final DnTableMetaMapper tableMetaMapper;

    @Value("${doris.database:ods}")
    private String hiveWarehouse;

    /** 排除的 Hive 系统库 */
    private static final Set<String> SYS_DBS = new HashSet<String>(Arrays.asList(
            "default", "information_schema", "sys"
    ));

    // ========== Hive 元数据查询 ==========

    /**
     * 获取 Hive 数据库列表（排除系统库）
     */
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

    /**
     * 获取 Hive 指定库的表列表
     */
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

    /**
     * 获取 Doris 指定表的字段信息。
     */
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

    // ========== 搜索 ==========

    public List<Map<String, Object>> searchTables(String keyword) throws SQLException {
        List<Map<String, Object>> allTables = getAllTablesSummary();
        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        String kw = keyword.toLowerCase();
        for (Map<String, Object> t : allTables) {
            String tableName = String.valueOf(t.get("TABLE_NAME")).toLowerCase();
            String dbName = String.valueOf(t.get("TABLE_SCHEMA")).toLowerCase();
            String comment = t.get("TABLE_COMMENT") != null ? String.valueOf(t.get("TABLE_COMMENT")).toLowerCase() : "";
            if (tableName.contains(kw) || dbName.contains(kw) || comment.contains(kw)) {
                matched.add(t);
                if (matched.size() >= 50) break;
            }
        }
        return matched;
    }

    public Map<String, Object> aiSearch(String query) throws Exception {
        List<Map<String, Object>> allTables = getAllTablesSummary();
        StringBuilder tableList = new StringBuilder();
        for (Map<String, Object> t : allTables) {
            tableList.append(t.get("TABLE_SCHEMA")).append(".").append(t.get("TABLE_NAME"));
            Object comment = t.get("TABLE_COMMENT");
            if (comment != null && !comment.toString().isEmpty()) {
                tableList.append(" (").append(comment).append(")");
            }
            tableList.append("\n");
        }

        String prompt = "你是一个数据资产搜索引擎。用户想找数据表，请根据用户描述匹配最相关的表。\n\n"
                + "可用的数据表列表：\n" + tableList.toString() + "\n"
                + "用户描述：" + query + "\n\n"
                + "请返回最匹配的表（最多5个），格式如下（严格JSON数组，不要其他文字）：\n"
                + "[{\"db\":\"库名\",\"table\":\"表名\",\"reason\":\"匹配原因\"}]";

        String aiReply = aiAssistService.chat(prompt, null);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("interpretation", "AI 正在为您分析：" + query);
        result.put("raw", aiReply);
        String jsonStr = extractJsonArray(aiReply);
        if (jsonStr != null) {
            result.put("tables", jsonStr);
        }
        return result;
    }

    /**
     * 获取所有 Hive 表的摘要信息
     */
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

    // ========== 搜索历史 ==========

    public List<DnSearchHistory> getSearchHistory() {
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.orderByDesc("searched_at").last("LIMIT 10");
        return searchHistoryMapper.selectList(qw);
    }

    public void addSearchHistory(DnSearchHistory history) {
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.eq("database_name", history.getDatabaseName()).eq("table_name", history.getTableName());
        DnSearchHistory existing = searchHistoryMapper.selectOne(qw);
        if (existing != null) {
            existing.setSearchedAt(LocalDateTime.now());
            searchHistoryMapper.updateById(existing);
        } else {
            history.setSearchedAt(LocalDateTime.now());
            searchHistoryMapper.insert(history);
        }
    }

    public void clearSearchHistory(String createdBy) {
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.eq("created_by", createdBy);
        searchHistoryMapper.delete(qw);
    }

    // ========== 收藏 ==========

    public List<DnTableFavorite> getFavorites() {
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.orderByDesc("created_at").last("LIMIT 30");
        return tableFavoriteMapper.selectList(qw);
    }

    public boolean toggleFavorite(String db, String table) {
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.eq("database_name", db).eq("table_name", table);
        DnTableFavorite existing = tableFavoriteMapper.selectOne(qw);
        if (existing != null) {
            tableFavoriteMapper.deleteById(existing.getId());
            return false;
        } else {
            DnTableFavorite fav = new DnTableFavorite();
            fav.setDatabaseName(db);
            fav.setTableName(table);
            fav.setCreatedBy("default");
            fav.setCreatedAt(LocalDateTime.now());
            tableFavoriteMapper.insert(fav);
            return true;
        }
    }

    public boolean isFavorited(String db, String table) {
        QueryWrapper<DnTableFavorite> qw = new QueryWrapper<DnTableFavorite>();
        qw.eq("database_name", db).eq("table_name", table);
        return tableFavoriteMapper.selectCount(qw) > 0;
    }

    // ========== 热门表 ==========

    public List<Map<String, Object>> getPopularTables() throws SQLException {
        // 先从搜索历史中获取热门表
        QueryWrapper<DnSearchHistory> qw = new QueryWrapper<DnSearchHistory>();
        qw.groupBy("database_name", "table_name")
          .orderByDesc("count(*)").last("LIMIT 10")
          .select("database_name", "table_name", "count(*) as cnt");
        List<Map<String, Object>> historyList = searchHistoryMapper.selectMaps(qw);

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> h : historyList) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("TABLE_SCHEMA", h.get("database_name"));
            row.put("TABLE_NAME", h.get("table_name"));
            row.put("search_count", h.get("cnt"));
            row.put("TABLE_COMMENT", "");
            row.put("TABLE_ROWS", null);
            row.put("col_count", null);
            result.add(row);
        }

        // 不足 10 个则用全部表填充
        if (result.size() < 10) {
            List<Map<String, Object>> allTables = getAllTablesSummary();
            Set<String> existing = new HashSet<String>();
            for (Map<String, Object> t : result) {
                existing.add(t.get("TABLE_SCHEMA") + "." + t.get("TABLE_NAME"));
            }
            for (Map<String, Object> t : allTables) {
                if (result.size() >= 10) break;
                String key = t.get("TABLE_SCHEMA") + "." + t.get("TABLE_NAME");
                if (!existing.contains(key)) {
                    result.add(t);
                }
            }
        }
        return result;
    }

    // ========== 评论 ==========

    public List<DnTableComment> getComments(String db, String table) {
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        QueryWrapper<DnTableComment> qw = new QueryWrapper<DnTableComment>();
        qw.eq("table_meta_id", tableMetaId).orderByDesc("created_at");
        return tableCommentMapper.selectList(qw);
    }

    public DnTableComment addComment(String db, String table, String content) {
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        DnTableComment comment = new DnTableComment();
        comment.setTableMetaId(tableMetaId);
        comment.setContent(content.trim());
        comment.setCreatedBy("default");
        comment.setCreatedAt(LocalDateTime.now());
        tableCommentMapper.insert(comment);
        return comment;
    }

    public void deleteComment(Long id) {
        tableCommentMapper.deleteById(id);
    }

    // ========== 数据预览 ==========

    public Map<String, Object> preview(String db, String table) throws SQLException {
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + db + "." + table + " LIMIT 20")) {
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

    // ========== 数据探查 ==========

    public Map<String, Object> profile(String db, String table) throws SQLException {
        List<ColumnInfo> columns = getHiveColumns(db, table);
        List<Map<String, Object>> fieldStats = new ArrayList<Map<String, Object>>();

        // Hive 聚合查询较慢，先查总行数
        long totalRows = 0;
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + db + "." + table)) {
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
                            + "FROM " + db + "." + table;
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
        Map<String, String> result = new HashMap<String, String>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE " + db + "." + table)) {
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

    // ========== 表详情 ==========

    public Map<String, Object> getTableDetail(String db, String table) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();

        Map<String, Object> info = getDorisTableInfo(db, table);

        result.put("tableInfo", info);
        result.put("columns", getHiveColumns(db, table));
        result.put("favorited", isFavorited(db, table));
        Long tableMetaId = findTableMetaId(db, table);
        if (tableMetaId != null) {
            QueryWrapper<DnTableComment> cmtQw = new QueryWrapper<DnTableComment>();
            cmtQw.eq("table_meta_id", tableMetaId);
            result.put("commentCount", tableCommentMapper.selectCount(cmtQw));
        } else {
            result.put("commentCount", 0);
        }
        return result;
    }

    private Map<String, Object> getDorisTableInfo(String db, String table) throws SQLException {
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

    // ========== 内部方法 ==========

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

    private Long findTableMetaId(String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<DnTableMeta>();
        qw.eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        DnTableMeta meta = tableMetaMapper.selectOne(qw);
        return meta != null ? meta.getId() : null;
    }

    private Long getOrCreateTableMetaId(String db, String table) {
        Long id = findTableMetaId(db, table);
        if (id != null) return id;
        try {
            DnTableMeta meta = new DnTableMeta();
            meta.setDatasourceId(0L);
            meta.setDatabaseName(db);
            meta.setTableName(table);
            meta.setCreatedAt(LocalDateTime.now());
            meta.setUpdatedAt(LocalDateTime.now());
            tableMetaMapper.insert(meta);
            return meta.getId();
        } catch (Exception e) {
            Long retryId = findTableMetaId(db, table);
            if (retryId != null) return retryId;
            throw e;
        }
    }

    // ========== 分区信息 ==========

    /**
     * 获取 Doris 表的分区列表。DataNote 建的 ODS 表多为非分区表，
     * Doris 非分区表执行 SHOW PARTITIONS 会报错，此处捕获并返回空列表。
     */
    public List<Map<String, Object>> getPartitions(String db, String table) throws SQLException {
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

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        if (start == -1) return null;
        int end = text.lastIndexOf(']');
        if (end == -1 || end <= start) return null;
        return text.substring(start, end + 1);
    }
}
