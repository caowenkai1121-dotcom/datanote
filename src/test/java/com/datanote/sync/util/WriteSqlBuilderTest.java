package com.datanote.sync.util;

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
        String sql = WriteSqlBuilder.build("UPSERT", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `t_user` (`id`, `name`, `age`) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `age` = VALUES(`age`)",
            sql);
    }

    @Test
    void insert_buildsPlainInsert() {
        String sql = WriteSqlBuilder.build("INSERT", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void insertIgnore_buildsInsertIgnore() {
        String sql = WriteSqlBuilder.build("INSERT_IGNORE", "t_user", cols, pk);
        assertEquals(
            "INSERT IGNORE INTO `t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void upsert_withNoNonPkColumns_fallsBackToInsertIgnore() {
        String sql = WriteSqlBuilder.build("UPSERT", "t_kv", Collections.singletonList("id"),
            Collections.singletonList("id"));
        assertEquals("INSERT IGNORE INTO `t_kv` (`id`) VALUES (?)", sql);
    }
}
