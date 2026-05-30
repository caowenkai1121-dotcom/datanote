package com.datanote.sync.dialect;

import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.schema.TypeMappingService;
import com.datanote.sync.util.SqlIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Doris 方言：写SQL=plain INSERT，建表=UNIQUE KEY 模型 + DISTRIBUTED HASH，类型=mysqlToDoris。
 */
public class DorisDialect implements SqlDialect {

    private final TypeMappingService typeMappingService = new TypeMappingService();

    @Override
    public String name() {
        return "DORIS";
    }

    @Override
    public String writeSql(String writeMode, String db, String table, List<String> columns, List<String> pkColumns) {
        String quotedTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String colList = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";
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
        // Doris：主键列排前面
        List<ColumnDef> ordered = new ArrayList<>();
        columns.stream().filter(ColumnDef::isPrimaryKey).forEach(ordered::add);
        columns.stream().filter(c -> !c.isPrimaryKey()).forEach(ordered::add);
        for (ColumnDef c : ordered) {
            colLines.add("  " + SqlIdentifiers.quote(c.getName()) + " "
                    + mapColumnType(c.getColumnType())
                    + DdlSupport.comment(c.getComment()));
        }
        String pkList = pks.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        return "CREATE TABLE IF NOT EXISTS " + fullTable + " (\n"
                + String.join(",\n", colLines) + "\n"
                + ") UNIQUE KEY(" + pkList + ")\n"
                + "DISTRIBUTED BY HASH(" + pkList + ") BUCKETS 10\n"
                + "PROPERTIES (\"replication_num\" = \"1\")";
    }

    @Override
    public String mapColumnType(String mysqlColumnType) {
        return typeMappingService.mysqlToDoris(mysqlColumnType);
    }
}
