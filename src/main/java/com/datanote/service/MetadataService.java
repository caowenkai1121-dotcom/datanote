package com.datanote.service;

import com.datanote.model.ColumnInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 元数据查询服务 — 支持默认数据源和自定义外部连接
 */
@Service
@RequiredArgsConstructor
public class MetadataService {

    private final DataSource dataSource;

    private static final String SQL_DATABASES =
            "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
            + "WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys') "
            + "ORDER BY SCHEMA_NAME";

    private static final String SQL_TABLES =
            "SELECT TABLE_NAME FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' "
            + "ORDER BY TABLE_NAME";

    private static final String SQL_COLUMNS =
            "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, COLUMN_KEY, IS_NULLABLE, EXTRA "
            + "FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
            + "ORDER BY ORDINAL_POSITION";

    /**
     * 获取默认数据源的所有数据库（排除系统库）
     */
    public List<String> getDatabases() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return queryDatabases(conn);
        }
    }

    /**
     * 获取外部连接的所有数据库
     */
    public List<String> getDatabasesByConnection(String host, int port, String username, String password) throws SQLException {
        try (Connection conn = getExternalConnection(host, port, username, password)) {
            return queryDatabases(conn);
        }
    }

    /**
     * 获取默认数据源指定库的所有表
     */
    public List<String> getTables(String db) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return queryTables(conn, db);
        }
    }

    /**
     * 获取外部连接指定库的所有表
     */
    public List<String> getTablesByConnection(String host, int port, String username, String password, String db) throws SQLException {
        try (Connection conn = getExternalConnection(host, port, username, password)) {
            return queryTables(conn, db);
        }
    }

    /**
     * 获取默认数据源指定表的所有字段信息
     */
    public List<ColumnInfo> getColumns(String db, String table) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return queryColumns(conn, db, table);
        }
    }

    /**
     * 获取外部连接指定表的所有字段信息
     */
    public List<ColumnInfo> getColumnsByConnection(String host, int port, String username, String password, String db, String table) throws SQLException {
        try (Connection conn = getExternalConnection(host, port, username, password)) {
            return queryColumns(conn, db, table);
        }
    }

    // ========== 核心查询逻辑（消除重复） ==========

    private List<String> queryDatabases(Connection conn) throws SQLException {
        List<String> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL_DATABASES)) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        }
        return list;
    }

    private List<String> queryTables(Connection conn, String db) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_TABLES)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
            }
        }
        return list;
    }

    private List<ColumnInfo> queryColumns(Connection conn, String db, String table) throws SQLException {
        List<ColumnInfo> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_COLUMNS)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setType(rs.getString("COLUMN_TYPE"));
                    col.setComment(rs.getString("COLUMN_COMMENT"));
                    col.setKey(rs.getString("COLUMN_KEY"));
                    col.setNullable(rs.getString("IS_NULLABLE"));
                    col.setExtra(rs.getString("EXTRA"));
                    col.setHiveType("string");
                    list.add(col);
                }
            }
        }
        return list;
    }

    private Connection getExternalConnection(String host, int port, String username, String password) throws SQLException {
        String url = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";
        return DriverManager.getConnection(url, username, password);
    }

    // ===== DS-M8：PostgreSQL 元数据（databases=schema；tables 走标准 information_schema；columns 用 PG 变体） =====

    public static boolean isPg(String type) {
        if (type == null) return false;
        String t = type.trim().toUpperCase();
        return t.startsWith("POSTGRE") || t.equals("PG");
    }

    private static final String SQL_PG_SCHEMAS =
            "SELECT schema_name FROM information_schema.schemata "
            + "WHERE schema_name NOT IN ('information_schema','pg_catalog','pg_toast') ORDER BY schema_name";

    private Connection getExternalConnection(String type, String host, int port, String username,
                                             String password, String databaseName) throws SQLException {
        if (isPg(type)) {
            String db = (databaseName == null || databaseName.isEmpty()) ? "postgres" : databaseName;
            return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + db, username, password);
        }
        return getExternalConnection(host, port, username, password);
    }

    public List<String> getDatabasesByConnection(String type, String host, int port, String username,
                                                 String password, String databaseName) throws SQLException {
        try (Connection conn = getExternalConnection(type, host, port, username, password, databaseName)) {
            if (isPg(type)) {
                List<String> list = new ArrayList<>();
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SQL_PG_SCHEMAS)) {
                    while (rs.next()) list.add(rs.getString(1));
                }
                return list;
            }
            return queryDatabases(conn);
        }
    }

    public List<String> getTablesByConnection(String type, String host, int port, String username,
                                              String password, String databaseName, String db) throws SQLException {
        try (Connection conn = getExternalConnection(type, host, port, username, password, databaseName)) {
            return queryTables(conn, db); // information_schema.tables 对 MySQL/PG 通用
        }
    }

    public List<ColumnInfo> getColumnsByConnection(String type, String host, int port, String username,
                                                   String password, String databaseName, String db, String table) throws SQLException {
        try (Connection conn = getExternalConnection(type, host, port, username, password, databaseName)) {
            return isPg(type) ? queryPgColumns(conn, db, table) : queryColumns(conn, db, table);
        }
    }

    private List<ColumnInfo> queryPgColumns(Connection conn, String schema, String table) throws SQLException {
        java.util.Set<String> pks = new java.util.HashSet<>();
        String pkSql = "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ? AND tc.table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(pkSql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pks.add(rs.getString(1));
            }
        }
        List<ColumnInfo> list = new ArrayList<>();
        String sql = "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    String name = rs.getString("column_name");
                    col.setName(name);
                    col.setType(com.datanote.sync.connector.PostgresConnector.pgTypeToMysql(
                            rs.getString("data_type"),
                            (Integer) rs.getObject("character_maximum_length"),
                            (Integer) rs.getObject("numeric_precision"),
                            (Integer) rs.getObject("numeric_scale")));
                    col.setComment("");
                    col.setKey(pks.contains(name) ? "PRI" : "");
                    col.setNullable(rs.getString("is_nullable"));
                    col.setExtra("");
                    col.setHiveType("string");
                    list.add(col);
                }
            }
        }
        return list;
    }
}
