package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlConnectorIncrementalTest {

    @Test
    void buildIncrementalPageSql_firstPage_usesGreaterOrEqual() {
        String sql = MysqlConnector.buildIncrementalPageSql(
            "src_db", "t_order", Arrays.asList("id", "amount", "updated_at"), "updated_at", "id", true);
        assertEquals(
            "SELECT `id`, `amount`, `updated_at` FROM `src_db`.`t_order` "
            + "WHERE `updated_at` >= ? ORDER BY `updated_at` ASC, `id` ASC LIMIT ?", sql);
    }

    @Test
    void buildIncrementalPageSql_nextPage_usesCompositeCursor() {
        String sql = MysqlConnector.buildIncrementalPageSql(
            "src_db", "t_order", Arrays.asList("id", "amount", "updated_at"), "updated_at", "id", false);
        assertEquals(
            "SELECT `id`, `amount`, `updated_at` FROM `src_db`.`t_order` "
            + "WHERE (`updated_at` > ? OR (`updated_at` = ? AND `id` > ?)) "
            + "ORDER BY `updated_at` ASC, `id` ASC LIMIT ?", sql);
    }
}
