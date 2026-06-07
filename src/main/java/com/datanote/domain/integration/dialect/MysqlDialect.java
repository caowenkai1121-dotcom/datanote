package com.datanote.domain.integration.dialect;

import com.datanote.domain.integration.connector.ColumnDef;
import com.datanote.domain.integration.util.SqlIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL 方言：写SQL=ON DUPLICATE KEY/INSERT_IGNORE，建表=PRIMARY KEY 照搬源类型，类型=原样返回。
 */
public class MysqlDialect implements SqlDialect {

    @Override
    public String name() {
        return "MYSQL";
    }

    @Override
    public String writeSql(String writeMode, String db, String table, List<String> columns, List<String> pkColumns) {
        String quotedTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String colList = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String base = "INSERT INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";
        String insertIgnore = "INSERT IGNORE INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";

        if ("INSERT_IGNORE".equals(writeMode)) {
            return insertIgnore;
        }
        if ("UPSERT".equals(writeMode)) {
            List<String> setClauses = new ArrayList<>();
            for (String col : columns) {
                if (!pkColumns.contains(col)) {
                    String q = SqlIdentifiers.quote(col);
                    setClauses.add(q + " = VALUES(" + q + ")");
                }
            }
            if (setClauses.isEmpty()) {
                return insertIgnore;
            }
            return base + " ON DUPLICATE KEY UPDATE " + String.join(", ", setClauses);
        }
        return base;
    }

    @Override
    public String createTableDdl(String db, String table, List<ColumnDef> columns) {
        List<String> pks = columns.stream().filter(ColumnDef::isPrimaryKey)
                .map(ColumnDef::getName).collect(Collectors.toList());
        if (pks.isEmpty()) {
            throw new IllegalStateException("源表无主键，无法自动建表: " + table);
        }
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);

        List<String> colLines = new ArrayList<>();
        // MySQL：照搬源类型
        for (ColumnDef c : columns) {
            colLines.add("  " + SqlIdentifiers.quote(c.getName()) + " " + c.getColumnType()
                    + (c.isNullable() ? " NULL" : " NOT NULL")
                    + DdlSupport.comment(c.getComment()));
        }
        String pkList = pks.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        return "CREATE TABLE IF NOT EXISTS " + fullTable + " (\n"
                + String.join(",\n", colLines) + ",\n"
                + "  PRIMARY KEY (" + pkList + ")\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public String mapColumnType(String mysqlColumnType) {
        return mysqlColumnType;
    }
}
