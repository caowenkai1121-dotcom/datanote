package com.datanote.sync.schema;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeMappingServiceTest {

    private final TypeMappingService svc = new TypeMappingService();

    @Test
    void mysqlToDoris_integers() {
        assertEquals("TINYINT", svc.mysqlToDoris("tinyint"));
        assertEquals("INT", svc.mysqlToDoris("int(11)"));
        assertEquals("BIGINT", svc.mysqlToDoris("bigint(20) unsigned"));
    }

    @Test
    void mysqlToDoris_stringsAndText() {
        assertEquals("VARCHAR(150)", svc.mysqlToDoris("varchar(50)")); // 字节预留：长度*3
        assertEquals("STRING", svc.mysqlToDoris("text"));
        assertEquals("STRING", svc.mysqlToDoris("longtext"));
    }

    @Test
    void mysqlToDoris_dateAndDecimal() {
        assertEquals("DATETIME", svc.mysqlToDoris("datetime"));
        assertEquals("DATE", svc.mysqlToDoris("date"));
        assertEquals("DECIMAL(10,2)", svc.mysqlToDoris("decimal(10,2)"));
    }

    @Test
    void mysqlToDoris_unknownFallsBackToString() {
        assertEquals("STRING", svc.mysqlToDoris("geometry"));
        assertEquals("STRING", svc.mysqlToDoris("json"));
    }
}
