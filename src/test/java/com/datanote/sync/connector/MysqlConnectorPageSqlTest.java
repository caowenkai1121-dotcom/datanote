package com.datanote.sync.connector;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class MysqlConnectorPageSqlTest {
    @Test public void keysetNoFilterUnchanged() {
        assertEquals("SELECT `id`, `n` FROM `db`.`t` ORDER BY `id` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSql("db","t", Arrays.asList("id","n"), "id", false, ""));
    }
    @Test public void keysetWithCursorAndFilter() {
        assertEquals("SELECT `id` FROM `db`.`t` WHERE `id` > ? AND (`a` = 1) ORDER BY `id` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSql("db","t", Arrays.asList("id"), "id", true, "(`a` = 1)"));
    }
    @Test public void keysetFilterNoCursor() {
        assertEquals("SELECT `id` FROM `db`.`t` WHERE (`a` = 1) ORDER BY `id` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSql("db","t", Arrays.asList("id"), "id", false, "(`a` = 1)"));
    }
    @Test public void incrementalWithFilter() {
        assertEquals("SELECT `ts`, `id` FROM `db`.`t` WHERE `ts` >= ? AND (`a` = 1) ORDER BY `ts` ASC, `id` ASC LIMIT ?",
            MysqlConnector.buildIncrementalPageSql("db","t", Arrays.asList("ts","id"), "ts", "id", true, "(`a` = 1)"));
    }
}
