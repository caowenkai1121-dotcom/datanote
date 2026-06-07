package com.datanote.domain.integration.util;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteSqlBuilderTest {

    private final List<String> cols = Arrays.asList("id", "name", "age");
    private final List<String> pk = Collections.singletonList("id");

    @Test
    void upsert_mysql_buildsOnDuplicateKeyUpdate() {
        String sql = WriteSqlBuilder.build("UPSERT", "dst_db", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `dst_db`.`t_user` (`id`, `name`, `age`) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `age` = VALUES(`age`)",
            sql);
    }

    @Test
    void upsert_doris_buildsPlainInsert() {
        String sql = WriteSqlBuilder.build("DORIS", "UPSERT", "dst_db", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `dst_db`.`t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void insertIgnore_doris_buildsPlainInsert() {
        String sql = WriteSqlBuilder.build("DORIS", "INSERT_IGNORE", "dst_db", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `dst_db`.`t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void insert_buildsPlainInsert() {
        String sql = WriteSqlBuilder.build("INSERT", "dst_db", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `dst_db`.`t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void insertIgnore_buildsInsertIgnore() {
        String sql = WriteSqlBuilder.build("INSERT_IGNORE", "dst_db", "t_user", cols, pk);
        assertEquals(
            "INSERT IGNORE INTO `dst_db`.`t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void upsert_withNoNonPkColumns_fallsBackToInsertIgnore() {
        String sql = WriteSqlBuilder.build("UPSERT", "dst_db", "t_kv", Collections.singletonList("id"),
            Collections.singletonList("id"));
        assertEquals("INSERT IGNORE INTO `dst_db`.`t_kv` (`id`) VALUES (?)", sql);
    }
}
