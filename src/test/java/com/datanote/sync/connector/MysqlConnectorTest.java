package com.datanote.domain.integration.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlConnectorTest {

    @Test
    void buildKeysetPageSql_firstPage_noWhere() {
        String sql = MysqlConnector.buildKeysetPageSql(
            "src_db", "t_user", Arrays.asList("id", "name"), "id", false);
        assertEquals(
            "SELECT `id`, `name` FROM `src_db`.`t_user` ORDER BY `id` ASC LIMIT ?", sql);
    }

    @Test
    void buildKeysetPageSql_nextPage_withWhere() {
        String sql = MysqlConnector.buildKeysetPageSql(
            "src_db", "t_user", Arrays.asList("id", "name"), "id", true);
        assertEquals(
            "SELECT `id`, `name` FROM `src_db`.`t_user` WHERE `id` > ? ORDER BY `id` ASC LIMIT ?", sql);
    }

    @Test
    void buildCountSql() {
        assertEquals("SELECT COUNT(*) FROM `src_db`.`t_user`",
            MysqlConnector.buildCountSql("src_db", "t_user"));
    }
}
