package com.datanote.domain.integration.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

public class MysqlConnectorMultiPkTest {

    @Test void multiNoCursor() {
        assertEquals("SELECT `a`, `b` FROM `db`.`t` ORDER BY `a` ASC, `b` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSqlMulti("db","t", Arrays.asList("a","b"), Arrays.asList("a","b"), false, ""));
    }

    @Test void multiWithCursor() {
        assertEquals("SELECT `a`, `b` FROM `db`.`t` WHERE (`a`, `b`) > (?, ?) ORDER BY `a` ASC, `b` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSqlMulti("db","t", Arrays.asList("a","b"), Arrays.asList("a","b"), true, ""));
    }

    @Test void multiCursorAndFilter() {
        assertEquals("SELECT `a` FROM `db`.`t` WHERE (`a`) > (?) AND (`x`=1) ORDER BY `a` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSqlMulti("db","t", Arrays.asList("a"), Arrays.asList("a"), true, "(`x`=1)"));
    }

    @Test void fullScanNoFilter() {
        assertEquals("SELECT `a`, `b` FROM `db`.`t`",
            MysqlConnector.buildFullScanSql("db","t", Arrays.asList("a","b"), ""));
    }

    @Test void fullScanFilter() {
        assertEquals("SELECT `a` FROM `db`.`t` WHERE (`x`=1)",
            MysqlConnector.buildFullScanSql("db","t", Arrays.asList("a"), "(`x`=1)"));
    }
}
