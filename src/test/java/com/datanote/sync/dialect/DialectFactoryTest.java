package com.datanote.domain.integration.dialect;

import com.datanote.domain.integration.connector.ColumnDef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DialectFactoryTest {

    @Test
    void factorySelectsDialect() {
        assertEquals("MYSQL", DialectFactory.of("MYSQL").name());
        assertEquals("DORIS", DialectFactory.of("DORIS").name());
        assertEquals("STARROCKS", DialectFactory.of("STARROCKS").name());
        assertEquals("MYSQL", DialectFactory.of(null).name()); // 缺省 MySQL
    }

    @Test
    void mysqlWriteUpsert() {
        String s = DialectFactory.of("MYSQL").writeSql("UPSERT", "db", "t",
                Arrays.asList("id", "name"), Arrays.asList("id"));
        assertEquals("INSERT INTO `db`.`t` (`id`, `name`) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)", s);
    }

    @Test
    void dorisWritePlainInsert() {
        String s = DialectFactory.of("DORIS").writeSql("UPSERT", "db", "t",
                Arrays.asList("id", "name"), Arrays.asList("id"));
        assertEquals("INSERT INTO `db`.`t` (`id`, `name`) VALUES (?, ?)", s);
    }

    @Test
    void mysqlTypeIdentity() {
        assertEquals("varchar(50)", DialectFactory.of("MYSQL").mapColumnType("varchar(50)"));
    }

    @Test
    void dorisTypeMapped() {
        assertEquals("STRING", DialectFactory.of("DORIS").mapColumnType("text"));
    }
}
