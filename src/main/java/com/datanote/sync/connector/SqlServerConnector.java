package com.datanote.sync.connector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL Server 源连接器（DS-M9）。
 * <p>「db」语义为 schema（默认 dbo；连接库取数据源 databaseName，由 ConnectionManager 处理）；
 * 标识符方括号引用；分页用 OFFSET/FETCH（参数末位，绑定顺序同 MySQL LIMIT）；
 * 列类型归一为 MySQL 词汇复用 mysqlToDoris/DorisDialect 自动建表。
 * <p>第一版：单主键表全量/增量 keyset + COUNT 对账；复合主键(SQLServer 不支持行值比较)、
 * 无主键流式、CDC、深度 checksum 为后续。
 */
public class SqlServerConnector implements DbConnector {

    private final ConnectionManager connectionManager;
    private final Long datasourceId;
    private final String defaultSchema;

    public SqlServerConnector(ConnectionManager connectionManager, Long datasourceId, String defaultSchema) {
        this.connectionManager = connectionManager;
        this.datasourceId = datasourceId;
        this.defaultSchema = (defaultSchema == null || defaultSchema.trim().isEmpty()) ? "dbo" : defaultSchema;
    }

    @Override
    public String getDatabaseType() {
        return "SQLSERVER";
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
                    col.setColumnType(sqlServerTypeToMysql(rs.getString("data_type"),
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
    public static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    /** SQL Server data_type → MySQL 列类型词汇（复用 mysqlToDoris/DorisDialect）。 */
    public static String sqlServerTypeToMysql(String dataType, Integer charLen, Integer numPrec, Integer numScale) {
        if (dataType == null) return "text";
        String t = dataType.trim().toLowerCase();
        switch (t) {
            case "tinyint": return "tinyint";
            case "smallint": return "smallint";
            case "int": return "int";
            case "bigint": return "bigint";
            case "bit": return "tinyint(1)";
            case "real": return "float";
            case "float": return "double";
            case "money": case "smallmoney": return "decimal(19,4)";
            case "date": return "date";
            case "uniqueidentifier": return "varchar(64)";
            case "text": case "ntext": case "xml":
            case "varbinary": case "binary": case "image": return "text";
            case "datetime": case "datetime2": case "smalldatetime": case "datetimeoffset": return "datetime";
            case "time": return "varchar(32)";
            default:
                break;
        }
        if (t.equals("decimal") || t.equals("numeric")) {
            int p = numPrec == null ? 38 : Math.min(numPrec, 38);
            int s = numScale == null ? 0 : Math.min(Math.max(numScale, 0), p); // scale 必须 0..precision
            return "decimal(" + p + "," + s + ")";
        }
        if (t.equals("varchar") || t.equals("nvarchar")) {
            // SQLServer max(-1) 或超大 → text
            return (charLen == null || charLen < 0) ? "text" : "varchar(" + Math.min(charLen, 65533) + ")";
        }
        if (t.equals("char") || t.equals("nchar")) {
            return (charLen == null || charLen < 0) ? "char(1)" : "char(" + charLen + ")";
        }
        return "text";
    }

    // ===== DS-M9：T-SQL 方言读取 SQL（方括号引用 + OFFSET/FETCH 分页） =====

    private static String q(String id) {
        return "[" + id.replace("]", "]]") + "]";
    }

    @Override
    public String quoteIdentifier(String id) {
        return q(id);
    }

    private String full(String db, String table) {
        return q(schema(db)) + "." + q(table);
    }

    private static void requireSinglePk(List<String> pkColumns) {
        if (pkColumns == null || pkColumns.size() != 1) {
            throw new IllegalStateException("SQL Server 源暂仅支持单主键 keyset/增量同步，当前主键数="
                    + (pkColumns == null ? 0 : pkColumns.size()));
        }
    }

    @Override
    public String scanSql(String db, String table, List<String> columns, String extraWhere) {
        String cols = columns.stream().map(SqlServerConnector::q).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(full(db, table));
        if (extraWhere != null && !extraWhere.trim().isEmpty()) sql.append(" WHERE ").append(extraWhere);
        return sql.toString();
    }

    @Override
    public String keysetPageSql(String db, String table, List<String> columns,
                                List<String> pkColumns, boolean hasCursor, String extraWhere) {
        requireSinglePk(pkColumns);
        String cols = columns.stream().map(SqlServerConnector::q).collect(Collectors.joining(", "));
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
        String cols = columns.stream().map(SqlServerConnector::q).collect(Collectors.joining(", "));
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
