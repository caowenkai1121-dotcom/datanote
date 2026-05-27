package com.datanote.sync.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成写入目标表的 SQL（参数占位符 ?，值由 PreparedStatement 绑定）。
 * 目标：MySQL 协议族（MySQL/Doris/StarRocks）。表名带库前缀全限定，不依赖连接默认库。
 */
public final class WriteSqlBuilder {

    private WriteSqlBuilder() {
    }

    /**
     * @param writeMode UPSERT / INSERT / INSERT_IGNORE
     * @param db        目标库
     * @param table     目标表名
     * @param columns   全部列（按绑定顺序）
     * @param pkColumns 主键列
     */
    public static String build(String writeMode, String db, String table, List<String> columns, List<String> pkColumns) {
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
}
