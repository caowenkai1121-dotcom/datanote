package com.datanote.domain.integration.schema;

import com.datanote.domain.integration.connector.ColumnDef;
import com.datanote.domain.integration.connector.DbConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
        return com.datanote.domain.integration.dialect.DialectFactory.of(targetType).createTableDdl(db, table, columns);
    }

    /**
     * 若目标表不存在则建表。存在则跳过。
     */
    public void ensureTargetTable(DbConnector target, String targetDb, String targetTable,
                                  List<ColumnDef> sourceColumns) throws SQLException {
        List<String> existing = target.listTables(targetDb);
        // 表名按 lower_case_table_names 可能大小写不敏感，忽略大小写判断避免重复建表报错
        if (existing != null && existing.stream().anyMatch(t -> t != null && t.equalsIgnoreCase(targetTable))) {
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
