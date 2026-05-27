package com.datanote.sync.schema;

import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.util.SqlIdentifiers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 目标表自动建表：生成 DDL（MySQL 照搬源类型 / Doris 用 Unique Key 模型）并执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableSchemaService {

    private final TypeMappingService typeMappingService;

    /**
     * 生成建表 DDL。
     * @param targetType MYSQL / DORIS / STARROCKS
     */
    public String buildDdl(String targetType, String db, String table, List<ColumnDef> columns) {
        List<String> pks = columns.stream().filter(ColumnDef::isPrimaryKey)
                .map(ColumnDef::getName).collect(Collectors.toList());
        if (pks.isEmpty()) {
            throw new IllegalStateException("源表无主键，无法自动建表: " + table);
        }
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        boolean doris = "DORIS".equalsIgnoreCase(targetType) || "STARROCKS".equalsIgnoreCase(targetType);

        List<String> colLines = new ArrayList<>();
        if (doris) {
            // Doris：主键列排前面
            List<ColumnDef> ordered = new ArrayList<>();
            columns.stream().filter(ColumnDef::isPrimaryKey).forEach(ordered::add);
            columns.stream().filter(c -> !c.isPrimaryKey()).forEach(ordered::add);
            for (ColumnDef c : ordered) {
                colLines.add("  " + SqlIdentifiers.quote(c.getName()) + " "
                        + typeMappingService.mysqlToDoris(c.getColumnType())
                        + comment(c.getComment()));
            }
            String pkList = pks.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
            return "CREATE TABLE IF NOT EXISTS " + fullTable + " (\n"
                    + String.join(",\n", colLines) + "\n"
                    + ") UNIQUE KEY(" + pkList + ")\n"
                    + "DISTRIBUTED BY HASH(" + pkList + ") BUCKETS 10\n"
                    + "PROPERTIES (\"replication_num\" = \"1\")";
        } else {
            // MySQL：照搬源类型
            for (ColumnDef c : columns) {
                colLines.add("  " + SqlIdentifiers.quote(c.getName()) + " " + c.getColumnType()
                        + (c.isNullable() ? " NULL" : " NOT NULL")
                        + comment(c.getComment()));
            }
            String pkList = pks.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
            return "CREATE TABLE IF NOT EXISTS " + fullTable + " (\n"
                    + String.join(",\n", colLines) + ",\n"
                    + "  PRIMARY KEY (" + pkList + ")\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        }
    }

    private String comment(String c) {
        if (c == null || c.isEmpty()) {
            return "";
        }
        return " COMMENT '" + c.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * 若目标表不存在则建表。存在则跳过。
     */
    public void ensureTargetTable(DbConnector target, String targetDb, String targetTable,
                                  List<ColumnDef> sourceColumns) throws SQLException {
        List<String> existing = target.listTables(targetDb);
        if (existing.contains(targetTable)) {
            log.info("目标表已存在，跳过建表: {}.{}", targetDb, targetTable);
            return;
        }
        String ddl = buildDdl(target.getDatabaseType(), targetDb, targetTable, sourceColumns);
        log.info("自动建表 DDL:\n{}", ddl);
        try (Connection conn = target.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(ddl);
        }
    }
}
