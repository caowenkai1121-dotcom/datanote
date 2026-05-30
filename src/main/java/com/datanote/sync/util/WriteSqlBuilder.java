package com.datanote.sync.util;

import java.util.List;

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
        return build("MYSQL", writeMode, db, table, columns, pkColumns);
    }

    public static String build(String targetType, String writeMode, String db, String table,
                               List<String> columns, List<String> pkColumns) {
        return com.datanote.sync.dialect.DialectFactory.of(targetType)
                .writeSql(writeMode, db, table, columns, pkColumns);
    }
}
