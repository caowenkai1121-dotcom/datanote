package com.datanote.sync.util;

import com.datanote.domain.integration.util.DorisSqlUtil;
import com.datanote.domain.metadata.model.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DorisSqlUtil 纯函数单测：列名归一/去重、标识符与字面量转义(防注入相关)。 */
class DorisSqlUtilTest {

    @Test
    void toDorisColumnName_normalizesCase_andSeparators() {
        assertEquals("user_name", DorisSqlUtil.toDorisColumnName("User Name"));
        assertEquals("a_b_c", DorisSqlUtil.toDorisColumnName("a--b__c"));
        assertEquals("col", DorisSqlUtil.toDorisColumnName("   "));
        assertEquals("col", DorisSqlUtil.toDorisColumnName(null));
        assertEquals("trim", DorisSqlUtil.toDorisColumnName("__trim__"));
    }

    @Test
    void toDorisColumnName_prefixesLeadingDigit() {
        assertEquals("c_1col", DorisSqlUtil.toDorisColumnName("1col"));
        assertEquals("c_2024", DorisSqlUtil.toDorisColumnName("2024"));
    }

    @Test
    void toDorisColumnNames_dedupsWithSuffix_andHandlesNull() {
        assertTrue(DorisSqlUtil.toDorisColumnNames(null).isEmpty());
        List<ColumnInfo> cols = new ArrayList<>();
        for (String n : Arrays.asList("name", "Name", "name")) {
            ColumnInfo c = new ColumnInfo();
            c.setName(n);
            cols.add(c);
        }
        List<String> out = DorisSqlUtil.toDorisColumnNames(cols);
        assertEquals(Arrays.asList("name", "name_2", "name_3"), out);
    }

    @Test
    void quoteIdentifier_wrapsAndEscapesBacktick() {
        assertEquals("`tbl`", DorisSqlUtil.quoteIdentifier("tbl"));
        assertEquals("`a``b`", DorisSqlUtil.quoteIdentifier("a`b"));
        assertEquals("`db`.`t`", DorisSqlUtil.quoteQualified("db", "t"));
    }

    @Test
    void quoteIdentifier_rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> DorisSqlUtil.quoteIdentifier(null));
        assertThrows(IllegalArgumentException.class, () -> DorisSqlUtil.quoteIdentifier("  "));
    }

    @Test
    void escapeSqlLiteral_escapesBackslashThenQuote() {
        assertEquals("", DorisSqlUtil.escapeSqlLiteral(null));
        assertEquals("ab", DorisSqlUtil.escapeSqlLiteral("ab"));
        assertEquals("o''brien", DorisSqlUtil.escapeSqlLiteral("o'brien"));
        // 反斜杠先转义，防尾部反斜杠把闭合单引号转义掉造成注入
        assertEquals("a\\\\b", DorisSqlUtil.escapeSqlLiteral("a\\b"));
        assertEquals("\\\\''", DorisSqlUtil.escapeSqlLiteral("\\'"));
    }
}
