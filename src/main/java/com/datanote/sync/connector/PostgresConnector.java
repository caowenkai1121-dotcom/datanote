package com.datanote.sync.connector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL 源连接器（DS-M8）。
 * <p>「db」语义为 PG schema（连接库取自数据源 databaseName，由 ConnectionManager 处理）；
 * 标识符双引号引用；元数据走 information_schema；列类型归一为 MySQL 词汇，复用既有
 * mysqlToDoris/DorisDialect 自动建表，无需新增类型映射。
 * <p>第一版：支持有主键表的全量/增量 keyset 同步 + COUNT 对账；无主键流式扫描与 CDC 暂不支持。
 */
public class PostgresConnector implements DbConnector {

    private final ConnectionManager connectionManager;
    private final Long datasourceId;
    private final String defaultSchema;

    public PostgresConnector(ConnectionManager connectionManager, Long datasourceId, String defaultSchema) {
        this.connectionManager = connectionManager;
        this.datasourceId = datasourceId;
        this.defaultSchema = (defaultSchema == null || defaultSchema.trim().isEmpty()) ? "public" : defaultSchema;
    }

    @Override
    public String getDatabaseType() {
        return "POSTGRESQL";
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionManager.getConnection(datasourceId, defaultSchema);
    }

    private String schema(String db) {
        return (db == null || db.trim().isEmpty()) ? defaultSchema : db;
    }

    @Override
    public TableMeta getTableMeta(String db, String table) throws SQLException {
        TableMeta meta = new TableMeta();
        String s = schema(db);
        String colSql = "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, s);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) meta.getColumns().add(rs.getString(1));
            }
        }
        meta.getPrimaryKeys().addAll(primaryKeys(s, table));
        return meta;
    }

    private List<String> primaryKeys(String schema, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        String sql = "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ? AND tc.table_name = ? "
                + "ORDER BY kcu.ordinal_position";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pks.add(rs.getString(1));
            }
        }
        return pks;
    }

    @Override
    public List<String> listTables(String db) throws SQLException {
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema(db));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) tables.add(rs.getString(1));
                return tables;
            }
        }
    }

    @Override
    public List<ColumnDef> getColumnDefs(String db, String table) throws SQLException {
        List<ColumnDef> list = new ArrayList<>();
        String s = schema(db);
        List<String> pks = primaryKeys(s, table);
        String sql = "SELECT column_name, data_type, is_nullable, character_maximum_length, "
                + "numeric_precision, numeric_scale FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, s);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnDef col = new ColumnDef();
                    String name = rs.getString("column_name");
                    col.setName(name);
                    col.setColumnType(pgTypeToMysql(rs.getString("data_type"),
                            toInt(rs.getObject("character_maximum_length")),
                            toInt(rs.getObject("numeric_precision")),
                            toInt(rs.getObject("numeric_scale"))));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    col.setPrimaryKey(pks.contains(name));
                    col.setComment("");
                    list.add(col);
                }
            }
        }
        return list;
    }

    /** information_schema 数值列在不同库返回 Short/Byte/Integer/BigDecimal，统一安全转 Integer。 */
    static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    /** PG data_type → MySQL 列类型词汇（下游 mysqlToDoris/DorisDialect 复用，零额外映射）。 */
    public static String pgTypeToMysql(String dataType, Integer charLen, Integer numPrec, Integer numScale) {
        if (dataType == null) return "text";
        String t = dataType.trim().toLowerCase();
        switch (t) {
            case "smallint": case "int2": return "smallint";
            case "integer": case "int": case "int4": case "serial": return "int";
            case "bigint": case "int8": case "bigserial": return "bigint";
            case "real": case "float4": return "float";
            case "double precision": case "float8": return "double";
            case "boolean": case "bool": return "tinyint(1)";
            case "date": return "date";
            case "text": case "json": case "jsonb": case "uuid": case "bytea": case "xml": return "text";
            default:
                break;
        }
        if (t.startsWith("numeric") || t.startsWith("decimal")) {
            int p = numPrec == null ? 38 : Math.min(numPrec, 38);
            int s = numScale == null ? 0 : numScale;
            return "decimal(" + p + "," + s + ")";
        }
        if (t.equals("character varying") || t.equals("varchar")) {
            return charLen == null ? "text" : "varchar(" + charLen + ")";
        }
        if (t.startsWith("character") || t.equals("char") || t.equals("bpchar")) {
            return charLen == null ? "char(1)" : "char(" + charLen + ")";
        }
        if (t.startsWith("timestamp")) return "datetime";
        if (t.startsWith("time")) return "varchar(32)";
        // 其余（数组/几何/网络等）统一 text → Doris STRING
        return "text";
    }

    // ===== DS-M8：PG 方言读取 SQL（双引号引用，LIMIT/行值比较与 MySQL 同） =====

    private static String q(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String quoteIdentifier(String id) {
        return q(id);
    }

    private String full(String db, String table) {
        return q(schema(db)) + "." + q(table);
    }

    @Override
    public String scanSql(String db, String table, List<String> columns, String extraWhere) {
        String cols = columns.stream().map(PostgresConnector::q).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(full(db, table));
        if (extraWhere != null && !extraWhere.trim().isEmpty()) sql.append(" WHERE ").append(extraWhere);
        return sql.toString();
    }

    @Override
    public String keysetPageSql(String db, String table, List<String> columns,
                                List<String> pkColumns, boolean hasCursor, String extraWhere) {
        String cols = columns.stream().map(PostgresConnector::q).collect(Collectors.joining(", "));
        String pkList = pkColumns.stream().map(PostgresConnector::q).collect(Collectors.joining(", "));
        String orderBy = pkColumns.stream().map(p -> q(p) + " ASC").collect(Collectors.joining(", "));
        String ph = pkColumns.stream().map(p -> "?").collect(Collectors.joining(", "));
        boolean hasFilter = extraWhere != null && !extraWhere.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(full(db, table));
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

    @Override
    public String incrementalPageSql(String db, String table, List<String> columns,
                                     String incField, List<String> pkColumns, boolean firstPage, String extraWhere) {
        String cols = columns.stream().map(PostgresConnector::q).collect(Collectors.joining(", "));
        String inc = q(incField);
        String pkList = pkColumns.stream().map(PostgresConnector::q).collect(Collectors.joining(", "));
        String ph = pkColumns.stream().map(p -> "?").collect(Collectors.joining(", "));
        String pkOrder = pkColumns.stream().map(p -> q(p) + " ASC").collect(Collectors.joining(", "));
        String where = firstPage ? inc + " >= ?"
                : "(" + inc + " > ? OR (" + inc + " = ? AND (" + pkList + ") > (" + ph + ")))";
        if (extraWhere != null && !extraWhere.trim().isEmpty()) where = where + " AND " + extraWhere;
        return "SELECT " + cols + " FROM " + full(db, table) + " WHERE " + where
                + " ORDER BY " + inc + " ASC, " + pkOrder + " LIMIT ?";
    }

    @Override
    public String countSql(String db, String table, String extraWhere) {
        String base = "SELECT COUNT(*) FROM " + full(db, table);
        if (extraWhere != null && !extraWhere.trim().isEmpty()) base += " WHERE " + extraWhere;
        return base;
    }
}
