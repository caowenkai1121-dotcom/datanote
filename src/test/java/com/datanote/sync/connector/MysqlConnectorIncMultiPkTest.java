package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 多列主键增量分页 SQL（(inc, pk1, pk2) 复合游标）首页/后页断言。 */
public class MysqlConnectorIncMultiPkTest {

    @Test
    void multiPk_firstPage() {
        String sql = MysqlConnector.buildIncrementalPageSqlMulti(
            "src_db", "t_order", Arrays.asList("a", "b", "updated_at"),
            "updated_at", Arrays.asList("a", "b"), true, "");
        assertEquals(
            "SELECT `a`, `b`, `updated_at` FROM `src_db`.`t_order` "
            + "WHERE `updated_at` >= ? ORDER BY `updated_at` ASC, `a` ASC, `b` ASC LIMIT ?", sql);
    }

    @Test
    void multiPk_nextPage() {
        String sql = MysqlConnector.buildIncrementalPageSqlMulti(
            "src_db", "t_order", Arrays.asList("a", "b", "updated_at"),
            "updated_at", Arrays.asList("a", "b"), false, "");
        assertEquals(
            "SELECT `a`, `b`, `updated_at` FROM `src_db`.`t_order` "
            + "WHERE (`updated_at` > ? OR (`updated_at` = ? AND (`a`, `b`) > (?, ?))) "
            + "ORDER BY `updated_at` ASC, `a` ASC, `b` ASC LIMIT ?", sql);
    }

    @Test
    void multiPk_nextPage_withFilter() {
        String sql = MysqlConnector.buildIncrementalPageSqlMulti(
            "src_db", "t_order", Arrays.asList("a", "ts"),
            "ts", Arrays.asList("a"), false, "(`x`=1)");
        assertEquals(
            "SELECT `a`, `ts` FROM `src_db`.`t_order` "
            + "WHERE (`ts` > ? OR (`ts` = ? AND (`a`) > (?))) AND (`x`=1) "
            + "ORDER BY `ts` ASC, `a` ASC LIMIT ?", sql);
    }
}
