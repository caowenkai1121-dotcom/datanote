package com.datanote.domain.integration.connector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M9：Oracle 方言 SQL / 类型归一 / URL。 */
public class OracleConnectorTest {

    private final OracleConnector or = new OracleConnector(null, 1L, "APP");
    private final List<String> cols = Arrays.asList("ID", "NAME");
    private final List<String> pk = Arrays.asList("ID");

    @Test
    void keysetDoubleQuoteOffsetFetch() {
        String sql = or.keysetPageSql("APP", "ORACLE_USERS", cols, pk, true, null);
        assertEquals("SELECT \"ID\", \"NAME\" FROM \"APP\".\"ORACLE_USERS\" WHERE \"ID\" > ? ORDER BY \"ID\" ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY", sql);
    }

    @Test
    void incrementalCursor() {
        String sql = or.incrementalPageSql("APP", "T", cols, "UPDATED_AT", pk, false, null);
        assertTrue(sql.contains("(\"UPDATED_AT\" > ? OR (\"UPDATED_AT\" = ? AND \"ID\" > ?))"));
        assertTrue(sql.endsWith("OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"));
    }

    @Test
    void compositePkUnsupported() {
        assertThrows(IllegalStateException.class,
                () -> or.keysetPageSql("APP", "T", cols, Arrays.asList("A", "B"), true, null));
    }

    @Test
    void countAndScanUppercase() {
        assertEquals("SELECT COUNT(*) FROM \"APP\".\"ORACLE_USERS\"", or.countSql("APP", "oracle_users", null));
    }

    @Test
    void typeNormalization() {
        assertEquals("bigint", OracleConnector.oracleTypeToMysql("NUMBER", null, null, null));
        assertEquals("smallint", OracleConnector.oracleTypeToMysql("NUMBER", null, 4, 0));
        assertEquals("int", OracleConnector.oracleTypeToMysql("NUMBER", null, 9, 0));
        assertEquals("bigint", OracleConnector.oracleTypeToMysql("NUMBER", null, 18, 0));
        assertEquals("decimal(12,2)", OracleConnector.oracleTypeToMysql("NUMBER", null, 12, 2));
        assertEquals("varchar(100)", OracleConnector.oracleTypeToMysql("VARCHAR2", 100, null, null));
        assertEquals("datetime", OracleConnector.oracleTypeToMysql("DATE", null, null, null));
        assertEquals("datetime", OracleConnector.oracleTypeToMysql("TIMESTAMP(6)", null, null, null));
        assertEquals("double", OracleConnector.oracleTypeToMysql("BINARY_DOUBLE", null, null, null));
        assertEquals("text", OracleConnector.oracleTypeToMysql("CLOB", null, null, null));
    }

    @Test
    void url() {
        assertEquals("jdbc:oracle:thin:@//h:1521/XEPDB1", ConnectionManager.buildOracleUrl("h", 1521, "XEPDB1"));
        assertEquals("jdbc:oracle:thin:@//h:1521/XEPDB1", ConnectionManager.buildOracleUrl("h", 1521, null));
    }
}
