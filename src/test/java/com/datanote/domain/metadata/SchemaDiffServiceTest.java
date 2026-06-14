package com.datanote.domain.metadata;

import com.datanote.domain.metadata.model.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表结构对比纯函数单测 —— added/removed/changed/same 计算, 大小写与类型归一。
 */
class SchemaDiffServiceTest {

    private ColumnInfo col(String name, String type) {
        ColumnInfo c = new ColumnInfo();
        c.setName(name);
        c.setType(type);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> d, String key) {
        return (List<Map<String, Object>>) d.get(key);
    }

    @Test
    void compare_identicalTables() {
        Map<String, Object> d = SchemaDiffService.compare(
                Arrays.asList(col("id", "bigint"), col("name", "varchar(50)")),
                Arrays.asList(col("id", "bigint"), col("name", "varchar(50)")));
        assertTrue((Boolean) d.get("identical"));
        assertEquals(2, d.get("sameCount"));
        assertTrue(listOf(d, "added").isEmpty());
        assertTrue(listOf(d, "removed").isEmpty());
        assertTrue(listOf(d, "changed").isEmpty());
    }

    @Test
    void compare_addedAndRemoved() {
        Map<String, Object> d = SchemaDiffService.compare(
                Arrays.asList(col("id", "bigint"), col("old_col", "int")),
                Arrays.asList(col("id", "bigint"), col("new_col", "string")));
        assertFalse((Boolean) d.get("identical"));
        assertEquals(1, d.get("sameCount"));            // id 一致
        assertEquals(1, listOf(d, "added").size());     // new_col 右有左无
        assertEquals("new_col", listOf(d, "added").get(0).get("name"));
        assertEquals(1, listOf(d, "removed").size());   // old_col 左有右无
        assertEquals("old_col", listOf(d, "removed").get(0).get("name"));
    }

    @Test
    void compare_typeChanged() {
        Map<String, Object> d = SchemaDiffService.compare(
                Arrays.asList(col("amt", "int")),
                Arrays.asList(col("amt", "bigint")));
        assertFalse((Boolean) d.get("identical"));
        assertEquals(0, d.get("sameCount"));
        assertEquals(1, listOf(d, "changed").size());
        Map<String, Object> ch = listOf(d, "changed").get(0);
        assertEquals("amt", ch.get("name"));
        assertEquals("int", ch.get("leftType"));
        assertEquals("bigint", ch.get("rightType"));
    }

    @Test
    void compare_ignoresNameAndTypeCaseAndWhitespace() {
        Map<String, Object> d = SchemaDiffService.compare(
                Arrays.asList(col("ID", " BIGINT ")),
                Arrays.asList(col("id", "bigint")));
        assertTrue((Boolean) d.get("identical"));
        assertEquals(1, d.get("sameCount"));
    }

    @Test
    void compare_emptyBothIdentical() {
        Map<String, Object> d = SchemaDiffService.compare(new ArrayList<>(), null);
        assertTrue((Boolean) d.get("identical"));
        assertEquals(0, d.get("sameCount"));
    }
}
