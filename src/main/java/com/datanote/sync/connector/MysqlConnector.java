package com.datanote.sync.connector;

import com.datanote.sync.util.SqlIdentifiers;

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

    // ===== 纯逻辑：可单测的 SQL 构建 =====

    /** keyset 分页查询 SQL。hasCursor=true 时带 WHERE 游标。 */
    public static String buildKeysetPageSql(String db, String table, List<String> columns,
                                            String pkColumn, boolean hasCursor) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String pk = SqlIdentifiers.quote(pkColumn);
        StringBuilder sql = new StringBuilder("SELECT ").append(cols)
                .append(" FROM ").append(fullTable);
        if (hasCursor) {
            sql.append(" WHERE ").append(pk).append(" > ?");
        }
        sql.append(" ORDER BY ").append(pk).append(" ASC LIMIT ?");
        return sql.toString();
    }

    public static String buildCountSql(String db, String table) {
        return "SELECT COUNT(*) FROM " + SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
    }
}
