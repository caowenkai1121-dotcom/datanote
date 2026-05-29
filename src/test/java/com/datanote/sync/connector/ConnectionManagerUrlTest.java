package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * buildJdbcUrl 单测：批处理重写参数(B1) + extraParams 追加在后(可覆盖)。
 */
class ConnectionManagerUrlTest {

    @Test
    void urlContainsBatchRewriteParams() {
        String url = ConnectionManager.buildJdbcUrl("h", 3306, "db", null);
        assertTrue(url.startsWith("jdbc:mysql://h:3306/db?"), url);
        assertTrue(url.contains("rewriteBatchedStatements=true"), url);
        assertTrue(url.contains("cachePrepStmts=true"), url);
        assertTrue(url.contains("prepStmtCacheSize=250"), url);
    }

    @Test
    void extraParamsAppendedAfter() {
        String url = ConnectionManager.buildJdbcUrl("h", 3306, "db", "useSSL=true&serverTimezone=UTC");
        assertTrue(url.endsWith("&useSSL=true&serverTimezone=UTC"), url);
    }
}
