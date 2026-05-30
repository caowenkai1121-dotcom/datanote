package com.datanote.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落标稽核纯函数单测 —— 命名拆词 / 合规判定 / 类型比对，无 Spring 上下文。
 */
class StandardCheckTest {

    @Test
    void splitColumnNameByUnderscoreLowercased() {
        assertEquals(Arrays.asList("user", "name"), StandardService.splitColumnName("user_name"));
        assertEquals(Arrays.asList("order", "id"), StandardService.splitColumnName("Order_ID"));
        // 多重/首尾下划线应去空
        assertEquals(Arrays.asList("amt", "total"), StandardService.splitColumnName("__amt__total_"));
        assertTrue(StandardService.splitColumnName(null).isEmpty());
        assertTrue(StandardService.splitColumnName("   ").isEmpty());
    }

    @Test
    void namingCompliantWhenAllWordsHitRoots() {
        Set<String> roots = new HashSet<>(Arrays.asList("user", "name", "id", "amt"));
        assertTrue(StandardService.isNamingCompliant("user_name", roots));
        assertTrue(StandardService.isNamingCompliant("user_id", roots));
        // 含未知词 phone -> 不合规
        assertFalse(StandardService.isNamingCompliant("user_phone", roots));
        // 空列名 / 无词 -> 不合规
        assertFalse(StandardService.isNamingCompliant("", roots));
        assertFalse(StandardService.isNamingCompliant("___", roots));
    }

    @Test
    void nonCompliantWordsReturnsOnlyMisses() {
        Set<String> roots = new HashSet<>(Arrays.asList("user", "id", "name"));
        assertEquals(Collections.singletonList("phone"),
                StandardService.nonCompliantWords("user_phone", roots));
        assertTrue(StandardService.nonCompliantWords("user_name", roots).isEmpty());
        // 全不命中
        assertEquals(Arrays.asList("foo", "bar"),
                StandardService.nonCompliantWords("foo_bar", roots));
    }

    @Test
    void typeMatchesIgnoresLengthAndCase() {
        assertTrue(StandardService.typeMatches("varchar(50)", "VARCHAR"));
        assertTrue(StandardService.typeMatches("VARCHAR(255)", "varchar(64)"));
        assertTrue(StandardService.typeMatches("bigint", "BIGINT"));
        assertTrue(StandardService.typeMatches("int unsigned", "int"));
        // 不同主类型 -> 不匹配
        assertFalse(StandardService.typeMatches("varchar(50)", "int"));
        // 空标准类型 -> 不校验，视为匹配（不产生违规）
        assertTrue(StandardService.typeMatches("varchar(50)", ""));
        assertTrue(StandardService.typeMatches("varchar(50)", null));
        // 物理类型为空 -> 无法比对，视为匹配（不误报）
        assertTrue(StandardService.typeMatches(null, "int"));
    }

    @Test
    void rootsAreMatchedBothByEnAndAbbr() {
        // 词根集合通常含 word_en 与 abbr 两套，调用方已合并小写化
        Set<String> roots = new HashSet<>(Arrays.asList("amount", "amt", "number", "no"));
        assertTrue(StandardService.isNamingCompliant("amt_no", roots));
        assertTrue(StandardService.isNamingCompliant("amount_number", roots));
        List<String> miss = StandardService.nonCompliantWords("amt_xyz", roots);
        assertEquals(Collections.singletonList("xyz"), miss);
    }
}
