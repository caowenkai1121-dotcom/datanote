package com.datanote.domain.integration.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M10：表名正则批量匹配（白/黑名单，全匹配语义）。 */
public class RegexTableMatcherTest {

    private final List<String> tables = Arrays.asList(
            "user", "user_0", "user_1", "user_2", "order", "order_log", "tmp_x");

    @Test
    void emptyIncludeReturnsAll() {
        assertEquals(tables, RegexTableMatcher.match(tables, null, null));
        assertEquals(tables, RegexTableMatcher.match(tables, "", ""));
    }

    @Test
    void includeShardPattern() {
        // 分表汇聚典型：user_\d+ 匹配所有分表（全匹配，不含裸 user）
        List<String> m = RegexTableMatcher.match(tables, "user_\\d+", null);
        assertEquals(Arrays.asList("user_0", "user_1", "user_2"), m);
    }

    @Test
    void excludeWins() {
        List<String> m = RegexTableMatcher.match(tables, ".*", "tmp_.*");
        assertFalse(m.contains("tmp_x"));
        assertTrue(m.contains("order"));
    }

    @Test
    void fullMatchSemantics() {
        // order 全匹配 "order"，order_log 不匹配
        assertEquals(Arrays.asList("order"), RegexTableMatcher.match(tables, "order", null));
    }

    @Test
    void illegalRegexThrows() {
        assertThrows(java.util.regex.PatternSyntaxException.class,
                () -> RegexTableMatcher.match(tables, "user_[", null));
    }
}
