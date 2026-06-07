package com.datanote.domain.integration.connector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Oracle 源连接器（DS-M9）。
 * <p>「db」语义为 schema/owner（大写；未填用连接用户大写；连接库取数据源 databaseName=服务名，
 * 由 ConnectionManager 处理）；标识符双引号引用（Oracle 未加引号建对象时以大写存储，元数据返回大写，
 * 故名字直接取自 all_tables/all_tab_columns 即可匹配）；分页用 OFFSET/FETCH（12c+/XE21c）。
 * 列类型归一为 MySQL 词汇复用 mysqlToDoris/DorisDialect 自动建表。
 * <p>第一版：单主键表全量/增量 keyset + COUNT 对账；复合主键/无主键流式/CDC/深度 checksum 后续。
 */
public class OracleConnector implements DbConnector {

    private final ConnectionManager connectionManager;
    private final Long datasourceId;
    private final String defaultSchema;

    public OracleConnector(ConnectionManager connectionManager, Long datasourceId, String defaultSchema) {
        this.connectionManager = connectionManager;
        this.datasourceId = datasourceId;
        this.defaultSchema = (defaultSchema == null || defaultSchema.trim().isEmpty()) ? null : defaultSchema.toUpperCase();
    }

    @Override
    public String getDatabaseType() {
        return "ORACLE";
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionManager.getConnection(datasourceId, defaultSchema);
    }

    /** schema/owner 统一大写；未指定时取连接当前用户。 */
    private String owner(Connection conn, String db) throws SQLException {
        if (db != null && !db.trim().isEmpty()) return db.trim().toUpperCase();
        if (defaultSchema != null) return defaultSchema;
        return conn.getMetaData().getUserName().toUpperCase();
    }

    @Override
    public TableMeta getTableMeta(String db, String table) throws SQLException {
        TableMeta meta = new TableMeta();
        String t = table.toUpperCase();
        try (Connection conn = getConnection()) {
            String o = owner(conn, db);
            String colSql = "SELECT column_name FROM all_tab_columns WHERE owner = ? AND table_name = ? ORDER BY column_id";
            try (PreparedStatement ps = conn.prepareStatement(colSql)) {
                ps.setString(1, o);
                ps.setString(2, t);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) meta.getColumns().add(rs.getString(1));
                }
            }
            meta.getPrimaryKeys().addAll(primaryKeys(conn, o, t));
        }
        return meta;
    }

    private List<String> primaryKeys(Connection conn, String owner, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        String sql = "SELECT c.column_name FROM all_constraints k "
                + "JOIN all_cons_columns c ON k.constraint_name = c.constraint_name AND k.owner = c.owner "
                + "WHERE k.constraint_type = 'P' AND k.owner = ? AND k.table_name = ? ORDER BY c.position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pks.add(rs.getString(1));
            }
        }
        return pks;
    }

    @Override
    public List<String> listTables(String db) throws SQLException {
        try (Connection conn = getConnection()) {
            String o = owner(conn, db);
            String sql = "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY table_name";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, o);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> tables = new ArrayList<>();
                    while (rs.next()) tables.add(rs.getString(1));
                    return tables;
                }
            }
        }
    }

    @Override
    public List<ColumnDef> getColumnDefs(String db, String table) throws SQLException {
        List<ColumnDef> list = new ArrayList<>();
        String t = table.toUpperCase();
        try (Connection conn = getConnection()) {
            String o = owner(conn, db);
            List<String> pks = primaryKeys(conn, o, t);
            String sql = "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable "
                    + "FROM all_tab_columns WHERE owner = ? AND table_name = ? ORDER BY column_id";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, o);
                ps.setString(2, t);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ColumnDef col = new ColumnDef();
                        String name = rs.getString("column_name");
                        col.setName(name);
                        col.setColumnType(oracleTypeToMysql(rs.getString("data_type"),
                                SqlServerConnector.toInt(rs.getObject("data_length")),
                                SqlServerConnector.toInt(rs.getObject("data_precision")),
                                SqlServerConnector.toInt(rs.getObject("data_scale"))));
                        col.setNullable("Y".equalsIgnoreCase(rs.getString("nullable")));
                        col.setPrimaryKey(pks.contains(name));
                        col.setComment("");
                        list.add(col);
                    }
                }
            }
        }
        return list;
    }

    /** Oracle data_type → MySQL 列类型词汇（复用 mysqlToDoris/DorisDialect）。 */
    public static String oracleTypeToMysql(String dataType, Integer dataLength, Integer dataPrecision, Integer dataScale) {
        if (dataType == null) return "text";
        String t = dataType.trim().toLowerCase();
        if (t.equals("number")) {
            int scale = dataScale == null ? 0 : dataScale;
            if (scale > 0) {
                int p = dataPrecision == null ? 38 : Math.min(dataPrecision, 38);
                return "decimal(" + p + "," + Math.min(scale, p) + ")";
            }
            // 整数（scale=0）
            if (dataPrecision == null) return "bigint";
            if (dataPrecision <= 4) return "smallint";
            if (dataPrecision <= 9) return "int";
            if (dataPrecision <= 18) return "bigint";
            return "decimal(" + Math.min(dataPrecision, 38) + ",0)";
        }
        if (t.equals("float") || t.equals("binary_double")) return "double";
        if (t.equals("binary_float")) return "float";
        if (t.startsWith("timestamp") || t.equals("date")) return "datetime";
        if (t.equals("char") || t.equals("nchar")) {
            return (dataLength == null || dataLength <= 0) ? "char(1)" : "char(" + Math.min(dataLength, 255) + ")";
        }
        if (t.equals("varchar2") || t.equals("nvarchar2") || t.equals("varchar")) {
            return (dataLength == null || dataLength <= 0) ? "text" : "varchar(" + Math.min(dataLength, 65533) + ")";
        }
        // clob/nclob/long/blob/raw/rowid/xmltype 等统一 text → Doris STRING
        return "text";
    }

    // ===== DS-M9：Oracle 方言读取 SQL（双引号引用 + OFFSET/FETCH） =====

    private static String q(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String quoteIdentifier(String id) {
        return q(id);
    }

    private String full(String db, String table) {
        // db(owner) 与 table 取自元数据（已是大写存储名），直接引用
        String o = (db == null || db.trim().isEmpty()) ? (defaultSchema == null ? null : defaultSchema) : db.trim().toUpperCase();
        return (o == null ? "" : q(o) + ".") + q(table.toUpperCase());
    }

    private static void requireSinglePk(List<String> pkColumns) {
        if (pkColumns == null || pkColumns.size() != 1) {
            throw new IllegalStateException("Oracle 源暂仅支持单主键 keyset/增量同步，当前主键数="
                    + (pkColumns == null ? 0 : pkColumns.size()));
        }
    }

    @Override
    public String scanSql(String db, String table, List<String> columns, String extraWhere) {
        String cols = columns.stream().map(OracleConnector::q).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(full(db, table));
        if (extraWhere != null && !extraWhere.trim().isEmpty()) sql.append(" WHERE ").append(extraWhere);
        return sql.toString();
    }

    @Override
    public String keysetPageSql(String db, String table, List<String> columns,
                                List<String> pkColumns, boolean hasCursor, String extraWhere) {
        requireSinglePk(pkColumns);
        String cols = columns.stream().map(OracleConnector::q).collect(Collectors.joining(", "));
        String pk = q(pkColumns.get(0));
        boolean hasFilter = extraWhere != null && !extraWhere.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(full(db, table));
        if (hasCursor && hasFilter) {
            sql.append(" WHERE ").append(pk).append(" > ? AND ").append(extraWhere);
        } else if (hasCursor) {
            sql.append(" WHERE ").append(pk).append(" > ?");
        } else if (hasFilter) {
            sql.append(" WHERE ").append(extraWhere);
        }
        sql.append(" ORDER BY ").append(pk).append(" ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        return sql.toString();
    }

    @Override
    public String incrementalPageSql(String db, String table, List<String> columns,
                                     String incField, List<String> pkColumns, boolean firstPage, String extraWhere) {
        requireSinglePk(pkColumns);
        String cols = columns.stream().map(OracleConnector::q).collect(Collectors.joining(", "));
        String inc = q(incField);
        String pk = q(pkColumns.get(0));
        String where = firstPage ? inc + " >= ?"
                : "(" + inc + " > ? OR (" + inc + " = ? AND " + pk + " > ?))";
        if (extraWhere != null && !extraWhere.trim().isEmpty()) where = where + " AND " + extraWhere;
        return "SELECT " + cols + " FROM " + full(db, table) + " WHERE " + where
                + " ORDER BY " + inc + " ASC, " + pk + " ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
    }

    @Override
    public String countSql(String db, String table, String extraWhere) {
        String base = "SELECT COUNT(*) FROM " + full(db, table);
        if (extraWhere != null && !extraWhere.trim().isEmpty()) base += " WHERE " + extraWhere;
        return base;
    }
}
