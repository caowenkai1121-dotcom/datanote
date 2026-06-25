package com.datanote.domain.integration.connector;

import com.datanote.domain.integration.util.SqlIdentifiers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL 协议族连接器（MySQL/Doris/StarRocks）。
 */
public class MysqlConnector implements DbConnector {

    private final ConnectionManager connectionManager;
    private final Long datasourceId;
    private final String defaultDb;
    private final String databaseType;

    public MysqlConnector(ConnectionManager connectionManager, Long datasourceId,
                          String defaultDb, String databaseType) {
        this.connectionManager = connectionManager;
        this.datasourceId = datasourceId;
        this.defaultDb = defaultDb;
        this.databaseType = databaseType;
    }

    @Override
    public String getDatabaseType() {
        return databaseType;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionManager.getConnection(datasourceId, defaultDb);
    }

    @Override
    public TableMeta getTableMeta(String db, String table) throws SQLException {
        TableMeta meta = new TableMeta();
        String sql = "SELECT COLUMN_NAME, COLUMN_KEY FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    meta.getColumns().add(col);
                    if ("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY"))) {
                        meta.getPrimaryKeys().add(col);
                    }
                }
            }
        }
        return meta;
    }

    @Override
    public List<String> listTables(String db) throws SQLException {
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> tables = new java.util.ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                return tables;
            }
        }
    }

    @Override
    public List<ColumnDef> getColumnDefs(String db, String table) throws SQLException {
        List<ColumnDef> list = new java.util.ArrayList<>();
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnDef col = new ColumnDef();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setColumnType(rs.getString("COLUMN_TYPE"));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    col.setPrimaryKey("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")));
                    col.setComment(rs.getString("COLUMN_COMMENT"));
                    list.add(col);
                }
            }
        }
        return list;
    }

    // ===== DS-M8：DbConnector 源端方言 SQL（委托既有静态实现，行为 byte-identical） =====
    @Override
    public String scanSql(String db, String table, List<String> columns, String extraWhere) {
        return buildFullScanSql(db, table, columns, extraWhere);
    }

    @Override
    public String keysetPageSql(String db, String table, List<String> columns,
                                List<String> pkColumns, boolean hasCursor, String extraWhere) {
        return buildKeysetPageSqlMulti(db, table, columns, pkColumns, hasCursor, extraWhere);
    }

    @Override
    public String incrementalPageSql(String db, String table, List<String> columns,
                                     String incField, List<String> pkColumns, boolean firstPage, String extraWhere) {
        return buildIncrementalPageSqlMulti(db, table, columns, incField, pkColumns, firstPage, extraWhere);
    }

    @Override
    public String countSql(String db, String table, String extraWhere) {
        return buildCountSql(db, table, extraWhere);
    }

    @Override
    public String quoteIdentifier(String id) {
        return SqlIdentifiers.quote(id);
    }

    /** 廉价估算行数: information_schema.TABLES.TABLE_ROWS（即时、近似, 亿级表也毫秒返回）。失败返回 -1。 */
    @Override
    public long estimateRowCount(Connection conn, String db, String table) {
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { long v = rs.getLong(1); return rs.wasNull() ? -1L : v; }
            }
        } catch (Exception e) { /* 估算失败不影响同步 */ }
        return -1L;
    }

    // ===== 纯逻辑：可单测的 SQL 构建 =====

    /** keyset 分页查询 SQL。hasCursor=true 时带 WHERE 游标。 */
    public static String buildKeysetPageSql(String db, String table, List<String> columns,
                                            String pkColumn, boolean hasCursor) {
        return buildKeysetPageSql(db, table, columns, pkColumn, hasCursor, null);
    }

    public static String buildKeysetPageSql(String db, String table, List<String> columns,
                                            String pkColumn, boolean hasCursor, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String pk = SqlIdentifiers.quote(pkColumn);
        boolean hasFilter = extraWhere != null && !extraWhere.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fullTable);
        if (hasCursor && hasFilter) {
            sql.append(" WHERE ").append(pk).append(" > ? AND ").append(extraWhere);
        } else if (hasCursor) {
            sql.append(" WHERE ").append(pk).append(" > ?");
        } else if (hasFilter) {
            sql.append(" WHERE ").append(extraWhere);
        }
        sql.append(" ORDER BY ").append(pk).append(" ASC LIMIT ?");
        return sql.toString();
    }

    /** 复合主键 keyset 分页。pkColumns 顺序即 ORDER BY 顺序；游标用行值比较 (pk..)>(?..)。 */
    public static String buildKeysetPageSqlMulti(String db, String table, List<String> columns,
                                                 List<String> pkColumns, boolean hasCursor, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String pkList = pkColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String orderBy = pkColumns.stream().map(p -> SqlIdentifiers.quote(p) + " ASC").collect(Collectors.joining(", "));
        boolean hasFilter = extraWhere != null && !extraWhere.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fullTable);
        String ph = pkColumns.stream().map(p -> "?").collect(Collectors.joining(", "));
        if (hasCursor && hasFilter) {
            sql.append(" WHERE (").append(pkList).append(") > (").append(ph).append(") AND ").append(extraWhere);
        } else if (hasCursor) {
            sql.append(" WHERE (").append(pkList).append(") > (").append(ph).append(")");
        } else if (hasFilter) {
            sql.append(" WHERE ").append(extraWhere);
        }
        sql.append(" ORDER BY ").append(orderBy).append(" LIMIT ?");
        return sql.toString();
    }

    /** 无主键全表流式扫描 SQL（无 ORDER/LIMIT，配合 setFetchSize(Integer.MIN_VALUE)）。 */
    public static String buildFullScanSql(String db, String table, List<String> columns, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fullTable);
        if (extraWhere != null && !extraWhere.trim().isEmpty()) sql.append(" WHERE ").append(extraWhere);
        return sql.toString();
    }

    public static String buildCountSql(String db, String table) {
        return buildCountSql(db, table, null);
    }

    /** COUNT SQL，extraWhere 非空才接 WHERE。 */
    public static String buildCountSql(String db, String table, String extraWhere) {
        String base = "SELECT COUNT(*) FROM " + SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        if (extraWhere != null && !extraWhere.trim().isEmpty()) {
            base += " WHERE " + extraWhere;
        }
        return base;
    }

    /** 增量分页查询 SQL（(incField, pk) 复合游标，避免同值跨页丢数据/死循环）。
     *  firstPage=true：incField >= ?（含起始断点，配合 UPSERT 幂等）；
     *  firstPage=false：incField > ? OR (incField = ? AND pk > ?)。 */
    public static String buildIncrementalPageSql(String db, String table, List<String> columns,
                                                 String incField, String pkColumn, boolean firstPage) {
        return buildIncrementalPageSql(db, table, columns, incField, pkColumn, firstPage, null);
    }

    public static String buildIncrementalPageSql(String db, String table, List<String> columns,
                                                 String incField, String pkColumn, boolean firstPage, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String inc = SqlIdentifiers.quote(incField);
        String pk = SqlIdentifiers.quote(pkColumn);
        String where = firstPage ? inc + " >= ?"
                : "(" + inc + " > ? OR (" + inc + " = ? AND " + pk + " > ?))";
        if (extraWhere != null && !extraWhere.trim().isEmpty()) where = where + " AND " + extraWhere;
        return "SELECT " + cols + " FROM " + fullTable + " WHERE " + where
                + " ORDER BY " + inc + " ASC, " + pk + " ASC LIMIT ?";
    }

    /** 多列主键增量分页 SQL（(incField, pk1, pk2..) 复合游标）。pkColumns 顺序即 ORDER BY 顺序。
     *  firstPage=true：incField >= ?（含起始断点，配合 UPSERT 幂等）；
     *  firstPage=false：incField > ? OR (incField = ? AND (pk1,..) > (?,..))。 */
    public static String buildIncrementalPageSqlMulti(String db, String table, List<String> columns,
                                                      String incField, List<String> pkColumns,
                                                      boolean firstPage, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String inc = SqlIdentifiers.quote(incField);
        String pkList = pkColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String ph = pkColumns.stream().map(p -> "?").collect(Collectors.joining(", "));
        String pkOrder = pkColumns.stream().map(p -> SqlIdentifiers.quote(p) + " ASC").collect(Collectors.joining(", "));
        String where = firstPage ? inc + " >= ?"
                : "(" + inc + " > ? OR (" + inc + " = ? AND (" + pkList + ") > (" + ph + ")))";
        if (extraWhere != null && !extraWhere.trim().isEmpty()) where = where + " AND " + extraWhere;
        return "SELECT " + cols + " FROM " + fullTable + " WHERE " + where
                + " ORDER BY " + inc + " ASC, " + pkOrder + " LIMIT ?";
    }
}
