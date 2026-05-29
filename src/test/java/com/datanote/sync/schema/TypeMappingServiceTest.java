package com.datanote.sync.schema;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeMappingServiceTest {

    private final TypeMappingService svc = new TypeMappingService();

    @Test
    void mysqlToDoris_integers() {
        assertEquals("TINYINT", svc.mysqlToDoris("tinyint"));
        assertEquals("INT", svc.mysqlToDoris("int(11)"));
        assertEquals("BIGINT", svc.mysqlToDoris("bigint(20)"));
    }

    @Test
    void mysqlToDoris_unsignedPromotesToAvoidOverflow() {
        assertEquals("SMALLINT", svc.mysqlToDoris("tinyint unsigned"));
        assertEquals("INT", svc.mysqlToDoris("smallint unsigned"));
        assertEquals("BIGINT", svc.mysqlToDoris("int(10) unsigned"));
        assertEquals("LARGEINT", svc.mysqlToDoris("bigint(20) unsigned"));
    }

    @Test
    void mysqlToDoris_stringsAndText() {
        assertEquals("VARCHAR(150)", svc.mysqlToDoris("varchar(50)")); // 字节预留：长度*3
        assertEquals("STRING", svc.mysqlToDoris("text"));
        assertEquals("STRING", svc.mysqlToDoris("longtext"));
        // varchar 超 Doris 65533 上限退化 STRING（22000*3=66000>65533）
        assertEquals("STRING", svc.mysqlToDoris("varchar(22000)"));
        // char *3 超 255 退化 VARCHAR
        assertEquals("CHAR(30)", svc.mysqlToDoris("char(10)"));
        assertEquals("VARCHAR(765)", svc.mysqlToDoris("char(255)"));
    }

    @Test
    void mysqlToDoris_dateAndDecimal() {
        assertEquals("DATETIME", svc.mysqlToDoris("datetime"));
        assertEquals("DATE", svc.mysqlToDoris("date"));
        assertEquals("DECIMAL(10,2)", svc.mysqlToDoris("decimal(10,2)"));
        // 时间精度保留，避免毫秒/微秒被截断
        assertEquals("DATETIME(3)", svc.mysqlToDoris("datetime(3)"));
        assertEquals("DATETIME(6)", svc.mysqlToDoris("timestamp(6)"));
        // decimal 精度封顶 38
        assertEquals("DECIMAL(38,4)", svc.mysqlToDoris("decimal(50,4)"));
    }

    @Test
    void mysqlToDoris_unknownFallsBackToString() {
        assertEquals("STRING", svc.mysqlToDoris("geometry"));
        assertEquals("STRING", svc.mysqlToDoris("json"));
    }
}
