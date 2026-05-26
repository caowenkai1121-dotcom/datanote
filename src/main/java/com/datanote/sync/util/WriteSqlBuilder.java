package com.datanote.sync.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成写入目标表的 SQL（参数占位符 ?，值由 PreparedStatement 绑定）。
 * 目标：MySQL 协议族（MySQL/Doris/StarRocks）。
 */
public final class WriteSqlBuilder {

    private WriteSqlBuilder() {
    }

    /**
     * @param writeMode UPSERT / INSERT / INSERT_IGNORE
     * @param table     目标表名
     * @param columns   全部列（按绑定顺序）
     * @param pkColumns 主键列
     */
    public static String build(String writeMode, String table, List<String> columns, List<String> pkColumns) {
        String quotedTable = SqlIdentifiers.quote(table);
        String colList = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String base = "INSERT INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";

        if ("INSERT_IGNORE".equals(writeMode)) {
            return "INSERT IGNORE INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";
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
                // 没有非主键列可更新，退化为 INSERT IGNORE
                return "INSERT IGNORE INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";
            }
            return base + " ON DUPLICATE KEY UPDATE " + String.join(", ", setClauses);
        }
        // 默认 INSERT
        return base;
    }
}
