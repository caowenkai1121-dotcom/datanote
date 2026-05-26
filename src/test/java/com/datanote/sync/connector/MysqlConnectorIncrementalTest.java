package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlConnectorIncrementalTest {

    @Test
    void buildIncrementalPageSql_filtersAndOrdersByIncrementalField() {
        String sql = MysqlConnector.buildIncrementalPageSql(
            "src_db", "t_order", Arrays.asList("id", "amount", "updated_at"), "updated_at");
        assertEquals(
            "SELECT `id`, `amount`, `updated_at` FROM `src_db`.`t_order` "
            + "WHERE `updated_at` > ? ORDER BY `updated_at` ASC LIMIT ?", sql);
    }
}
