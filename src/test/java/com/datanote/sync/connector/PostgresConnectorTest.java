package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M8：PostgreSQL 方言 SQL / 类型归一 / 连接 URL；并护栏 MySQL 实例方法与静态 byte-identical。 */
public class PostgresConnectorTest {

    private final PostgresConnector pg = new PostgresConnector(null, 1L, "public");
    private final List<String> cols = Arrays.asList("id", "name");
    private final List<String> pk = Arrays.asList("id");

    @Test
    void pgKeysetQuotingAndLimit() {
        String sql = pg.keysetPageSql("public", "pg_users", cols, pk, true, null);
        assertEquals("SELECT \"id\", \"name\" FROM \"public\".\"pg_users\" WHERE (\"id\") > (?) ORDER BY \"id\" ASC LIMIT ?", sql);
    }

    @Test
    void pgKeysetNoCursor() {
        String sql = pg.keysetPageSql("public", "pg_users", cols, pk, false, null);
        assertEquals("SELECT \"id\", \"name\" FROM \"public\".\"pg_users\" ORDER BY \"id\" ASC LIMIT ?", sql);
    }

    @Test
    void pgIncrementalCursor() {
        String sql = pg.incrementalPageSql("public", "t", cols, "updated_at", pk, false, null);
        assertTrue(sql.contains("\"updated_at\" > ? OR (\"updated_at\" = ? AND (\"id\") > (?))"));
        assertTrue(sql.endsWith("LIMIT ?"));
    }

    @Test
    void pgCountAndScan() {
        assertEquals("SELECT COUNT(*) FROM \"public\".\"pg_users\"", pg.countSql("public", "pg_users", null));
        assertEquals("SELECT COUNT(*) FROM \"public\".\"pg_users\" WHERE age>18",
                pg.countSql("public", "pg_users", "age>18"));
        assertEquals("SELECT \"id\", \"name\" FROM \"public\".\"t\"", pg.scanSql("public", "t", cols, null));
    }

    @Test
    void pgTypeNormalizationToMysqlVocab() {
        assertEquals("int", PostgresConnector.pgTypeToMysql("integer", null, null, null));
        assertEquals("bigint", PostgresConnector.pgTypeToMysql("bigint", null, null, null));
        assertEquals("smallint", PostgresConnector.pgTypeToMysql("smallint", null, null, null));
        assertEquals("tinyint(1)", PostgresConnector.pgTypeToMysql("boolean", null, null, null));
        assertEquals("varchar(100)", PostgresConnector.pgTypeToMysql("character varying", 100, null, null));
        assertEquals("decimal(12,2)", PostgresConnector.pgTypeToMysql("numeric", null, 12, 2));
        assertEquals("datetime", PostgresConnector.pgTypeToMysql("timestamp without time zone", null, null, null));
        assertEquals("date", PostgresConnector.pgTypeToMysql("date", null, null, null));
        assertEquals("double", PostgresConnector.pgTypeToMysql("double precision", null, null, null));
        assertEquals("text", PostgresConnector.pgTypeToMysql("jsonb", null, null, null));
    }

    @Test
    void pgUrl() {
        assertEquals("jdbc:postgresql://10.0.0.1:5432/testdb",
                ConnectionManager.buildPgUrl("10.0.0.1", 5432, "testdb", null));
        assertEquals("jdbc:postgresql://h:5432/db?sslmode=disable",
                ConnectionManager.buildPgUrl("h", 5432, "db", "sslmode=disable"));
    }

    @Test
    void mysqlInstanceMethodsByteIdenticalToStatics() {
        MysqlConnector my = new MysqlConnector(null, 1L, "db", "MYSQL");
        assertEquals(MysqlConnector.buildKeysetPageSqlMulti("db", "t", cols, pk, true, "x>1"),
                my.keysetPageSql("db", "t", cols, pk, true, "x>1"));
        assertEquals(MysqlConnector.buildFullScanSql("db", "t", cols, null),
                my.scanSql("db", "t", cols, null));
        assertEquals(MysqlConnector.buildIncrementalPageSqlMulti("db", "t", cols, "u", pk, false, null),
                my.incrementalPageSql("db", "t", cols, "u", pk, false, null));
        assertEquals(MysqlConnector.buildCountSql("db", "t", null), my.countSql("db", "t", null));
    }
}
