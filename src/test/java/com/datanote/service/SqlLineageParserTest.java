package com.datanote.service;

import com.datanote.domain.orchestration.SqlLineageParser;
import com.datanote.domain.orchestration.SqlLineageParser.ColumnMapping;
import com.datanote.domain.orchestration.SqlLineageParser.ParseResult;
import com.datanote.domain.orchestration.SqlLineageParser.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlLineageParser 纯函数单测 — 覆盖 INSERT...SELECT / CTAS / 子查询 / 多JOIN / CTE / 解析失败降级。
 */
class SqlLineageParserTest {

    private List<String> reads(ParseResult r) {
        return r.getReadTables().stream().map(TableRef::full).sorted().collect(Collectors.toList());
    }

    @Test
    void insertSelect_singleSource() {
        ParseResult r = SqlLineageParser.parse(
                "INSERT INTO ods.ods_orders SELECT id, name FROM mall.orders", "ods");
        assertNotNull(r.getWriteTable());
        assertEquals("ods.ods_orders", r.getWriteTable().full());
        assertEquals(java.util.Collections.singletonList("mall.orders"), reads(r));
    }

    @Test
    void insertSelect_defaultDbFilled() {
        // 源表/目标表均不带库名，应补默认库
        ParseResult r = SqlLineageParser.parse(
                "INSERT INTO ods_orders SELECT id FROM orders", "ods");
        assertEquals("ods.ods_orders", r.getWriteTable().full());
        assertEquals(java.util.Collections.singletonList("ods.orders"), reads(r));
    }

    @Test
    void ctas_createTableAsSelect() {
        ParseResult r = SqlLineageParser.parse(
                "CREATE TABLE dw.dim_user AS SELECT uid, uname FROM ods.users", "dw");
        assertNotNull(r.getWriteTable());
        assertEquals("dw.dim_user", r.getWriteTable().full());
        assertEquals(java.util.Collections.singletonList("ods.users"), reads(r));
    }

    @Test
    void multiJoin_collectsAllSources() {
        ParseResult r = SqlLineageParser.parse(
                "INSERT INTO dw.wide SELECT o.id, u.name, p.title " +
                        "FROM ods.orders o JOIN ods.users u ON o.uid=u.id " +
                        "JOIN ods.products p ON o.pid=p.id", "ods");
        assertEquals("dw.wide", r.getWriteTable().full());
        assertEquals(java.util.Arrays.asList("ods.orders", "ods.products", "ods.users"), reads(r));
    }

    @Test
    void subquery_collectsInnerSource() {
        ParseResult r = SqlLineageParser.parse(
                "INSERT INTO dw.t SELECT a.id FROM (SELECT id FROM ods.src) a", "ods");
        assertEquals("dw.t", r.getWriteTable().full());
        // 子查询别名 a 不应作为物理源表，仅保留 ods.src
        assertEquals(java.util.Collections.singletonList("ods.src"), reads(r));
    }

    @Test
    void cte_withClause_excludesCteNameFromSources() {
        ParseResult r = SqlLineageParser.parse(
                "INSERT INTO dw.t WITH c AS (SELECT id FROM ods.base) " +
                        "SELECT id FROM c", "ods");
        assertEquals("dw.t", r.getWriteTable().full());
        // CTE 名 c 不是物理表，只保留 ods.base
        assertEquals(java.util.Collections.singletonList("ods.base"), reads(r));
    }

    @Test
    void columnMapping_directColumns() {
        ParseResult r = SqlLineageParser.parse(
                "INSERT INTO ods.ods_orders(order_id, cust) SELECT id, name FROM mall.orders", "ods");
        List<ColumnMapping> cms = r.getColumnMappings();
        // 至少能映射出 order_id<-id, cust<-name
        ColumnMapping orderId = cms.stream().filter(c -> "order_id".equals(c.getTargetColumn()))
                .findFirst().orElse(null);
        assertNotNull(orderId, "应有 order_id 列映射");
        assertEquals("id", orderId.getSrcColumn());
        assertEquals("mall", orderId.getSrcDb());
        assertEquals("orders", orderId.getSrcTable());
    }

    @Test
    void parseFailure_degradesToEmpty_noThrow() {
        ParseResult r = SqlLineageParser.parse("this is not valid sql @#$%", "ods");
        assertNull(r.getWriteTable());
        assertTrue(r.getReadTables().isEmpty());
        assertTrue(r.getColumnMappings().isEmpty());
    }

    @Test
    void pureSelect_noWriteTable() {
        // 纯查询无写目标，应降级为空写目标
        ParseResult r = SqlLineageParser.parse("SELECT id FROM ods.t", "ods");
        assertNull(r.getWriteTable());
    }

    @Test
    void nullOrBlankSql_safe() {
        assertNull(SqlLineageParser.parse(null, "ods").getWriteTable());
        assertTrue(SqlLineageParser.parse("   ", "ods").getReadTables().isEmpty());
    }
}
