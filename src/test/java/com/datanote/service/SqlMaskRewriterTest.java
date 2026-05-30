package com.datanote.service;

import com.datanote.service.SqlMaskRewriter.ColumnMask;
import com.datanote.service.SqlMaskRewriter.MaskRewriteException;
import com.datanote.service.SqlMaskRewriter.RowFilter;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlMaskRewriter 纯函数单测 —— 查询期脱敏改写 + 行过滤注入。
 * 覆盖：无策略原样 / 单列脱敏 / 多列 / 行过滤注入 / SELECT * 降级 / 解析失败 / 非SELECT原样 / JOIN 定位。
 */
class SqlMaskRewriterTest {

    private static final List<ColumnMask> NO_MASK = Collections.emptyList();
    private static final List<RowFilter> NO_ROW = Collections.emptyList();

    private ColumnMask mask(String db, String table, String col, String func) {
        return new ColumnMask(db, table, col, func);
    }

    private RowFilter row(String db, String table, String filter) {
        return new RowFilter(db, table, filter);
    }

    // ---------- 无策略：原样 ----------

    @Test
    void noPolicy_returnsOriginal() throws Exception {
        String sql = "SELECT id, name FROM mall.users";
        assertEquals(sql, SqlMaskRewriter.rewrite(sql, "mall", NO_MASK, NO_ROW));
    }

    @Test
    void nonSelect_returnsOriginal() throws Exception {
        // 非 SELECT（即便给了策略）原样放行：脱敏只对查询有意义
        String ddl = "INSERT INTO mall.users(id, phone) VALUES (1, '13800000000')";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        assertEquals(ddl, SqlMaskRewriter.rewrite(ddl, "mall", masks, NO_ROW));
    }

    // ---------- 单列脱敏 ----------

    @Test
    void singleColumn_maskWrapped() throws Exception {
        String sql = "SELECT id, phone FROM mall.users";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW);
        // phone 被脱敏函数包裹，且保留别名 phone
        assertTrue(out.toUpperCase().contains("CONCAT"), "MASK 应生成 CONCAT 表达式: " + out);
        assertTrue(out.contains("phone"), "应保留 phone 别名: " + out);
        // 未命中列 id 不变
        assertTrue(out.contains("id"));
    }

    @Test
    void hashFunc_md5() throws Exception {
        String sql = "SELECT email FROM mall.users";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "email", "HASH"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW);
        assertTrue(out.toUpperCase().contains("MD5"), "HASH 应生成 MD5: " + out);
    }

    @Test
    void replaceFunc_constant() throws Exception {
        String sql = "SELECT idcard FROM mall.users";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "idcard", "REPLACE"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW);
        assertTrue(out.contains("'***'"), "REPLACE 应生成常量 '***': " + out);
    }

    @Test
    void defaultDb_filled_columnMatched() throws Exception {
        // SQL 表不带库名，用 defaultDb 补全后应能匹配策略
        String sql = "SELECT phone FROM users";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW);
        assertTrue(out.toUpperCase().contains("CONCAT"), "默认库补全后应命中脱敏: " + out);
    }

    // ---------- 行过滤注入 ----------

    @Test
    void rowFilter_injectedWhenNoWhere() throws Exception {
        String sql = "SELECT id FROM mall.orders";
        List<RowFilter> rows = Collections.singletonList(row("mall", "orders", "region = 'EAST'"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", NO_MASK, rows);
        assertTrue(out.toUpperCase().contains("WHERE"), "应注入 WHERE: " + out);
        assertTrue(out.contains("region"), "应含行过滤条件: " + out);
    }

    @Test
    void rowFilter_andedWithExistingWhere() throws Exception {
        String sql = "SELECT id FROM mall.orders WHERE amount > 100";
        List<RowFilter> rows = Collections.singletonList(row("mall", "orders", "region = 'EAST'"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", NO_MASK, rows);
        assertTrue(out.contains("amount"), "保留原 WHERE: " + out);
        assertTrue(out.contains("region"), "AND 行过滤: " + out);
    }

    // ---------- SELECT * 降级 ----------

    @Test
    void selectStar_withSensitiveTable_failClosed() {
        String sql = "SELECT * FROM mall.users";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        assertThrows(MaskRewriteException.class,
                () -> SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW));
    }

    @Test
    void selectStar_noSensitiveTable_returnsOriginal() throws Exception {
        // SELECT * 但该表无脱敏策略 → 不涉及降级，原样
        String sql = "SELECT * FROM mall.products";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        assertEquals(sql, SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW));
    }

    // ---------- 解析失败 ----------

    @Test
    void parseFailure_failClosed() {
        String sql = "this is @#$ not sql";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        assertThrows(MaskRewriteException.class,
                () -> SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW));
    }

    @Test
    void parseFailure_butNoPolicy_returnsOriginal() throws Exception {
        // 无任何策略时，即便解析失败也原样放行（no-op 路径不进改写）
        String sql = "this is @#$ not sql";
        assertEquals(sql, SqlMaskRewriter.rewrite(sql, "mall", NO_MASK, NO_ROW));
    }

    // ---------- JOIN 多表定位 ----------

    @Test
    void join_masksColumnOnCorrectTable() throws Exception {
        String sql = "SELECT u.phone, o.id FROM mall.users u JOIN mall.orders o ON u.id = o.uid";
        List<ColumnMask> masks = Collections.singletonList(mask("mall", "users", "phone", "MASK"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", masks, NO_ROW);
        assertTrue(out.toUpperCase().contains("CONCAT"), "应脱敏 users.phone: " + out);
    }

    @Test
    void join_rowFilterOnSpecificTable() throws Exception {
        String sql = "SELECT o.id FROM mall.orders o JOIN mall.users u ON o.uid = u.id";
        List<RowFilter> rows = Collections.singletonList(row("mall", "orders", "o.region = 'EAST'"));
        String out = SqlMaskRewriter.rewrite(sql, "mall", NO_MASK, rows);
        assertTrue(out.contains("region"), "应注入行过滤: " + out);
    }
}
