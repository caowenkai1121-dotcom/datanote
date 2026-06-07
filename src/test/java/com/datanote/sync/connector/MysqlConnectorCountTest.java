package com.datanote.domain.integration.connector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** buildCountSql 三参 filter 重载与旧二参委托。 */
public class MysqlConnectorCountTest {

    @Test
    void noFilter() {
        assertEquals("SELECT COUNT(*) FROM `db`.`t`", MysqlConnector.buildCountSql("db", "t", ""));
    }

    @Test
    void withFilter() {
        assertEquals("SELECT COUNT(*) FROM `db`.`t` WHERE (`a`=1)", MysqlConnector.buildCountSql("db", "t", "(`a`=1)"));
    }

    @Test
    void legacy2arg() {
        assertEquals("SELECT COUNT(*) FROM `db`.`t`", MysqlConnector.buildCountSql("db", "t"));
    }
}
