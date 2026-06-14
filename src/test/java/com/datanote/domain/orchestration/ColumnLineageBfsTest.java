package com.datanote.domain.orchestration;

import com.datanote.domain.orchestration.model.DnLineageEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字段级血缘穿透纯函数单测 —— 多跳下游影响 / 上游溯源 / 去重防环。
 */
class ColumnLineageBfsTest {

    private DnLineageEdge col(String sdb, String stab, String scol, String ddb, String dtab, String dcol, String transform) {
        DnLineageEdge e = new DnLineageEdge();
        e.setLevelType("COLUMN");
        e.setSrcDb(sdb); e.setSrcTable(stab); e.setSrcColumn(scol);
        e.setDstDb(ddb); e.setDstTable(dtab); e.setDstColumn(dcol);
        e.setTransformType(transform);
        e.setSource("SQL");
        return e;
    }

    // ods.t.a -> dwd.t.b -> ads.t.c   (单链)
    private List<DnLineageEdge> chain() {
        List<DnLineageEdge> es = new ArrayList<>();
        es.add(col("ods", "t", "a", "dwd", "t", "b", "DIRECT"));
        es.add(col("dwd", "t", "b", "ads", "t", "c", "TRANSFORM"));
        return es;
    }

    @Test
    void columnImpact_downstreamMultiHop() {
        List<Map<String, Object>> ds = LineageEdgeService.computeColumnBfs(chain(), "ods", "t", "a", true, 10);
        assertEquals(2, ds.size());
        assertEquals("b", ds.get(0).get("column"));
        assertEquals(1, ds.get(0).get("depth"));
        assertEquals("c", ds.get(1).get("column"));
        assertEquals(2, ds.get(1).get("depth"));
        assertEquals("TRANSFORM", ds.get(1).get("transformType"));
    }

    @Test
    void columnTrace_upstreamMultiHop() {
        List<Map<String, Object>> us = LineageEdgeService.computeColumnBfs(chain(), "ads", "t", "c", false, 10);
        assertEquals(2, us.size());
        assertEquals("b", us.get(0).get("column"));   // 第一跳上游
        assertEquals("a", us.get(1).get("column"));   // 第二跳源头
    }

    @Test
    void columnBfs_caseInsensitiveStart() {
        // 起点大小写不敏感: 用大写 A 也能命中 ods.t.a 的下游
        List<Map<String, Object>> ds = LineageEdgeService.computeColumnBfs(chain(), "ODS", "T", "A", true, 10);
        assertEquals(2, ds.size());
    }

    @Test
    void columnBfs_depthLimit() {
        List<Map<String, Object>> ds = LineageEdgeService.computeColumnBfs(chain(), "ods", "t", "a", true, 1);
        assertEquals(1, ds.size());   // 仅一跳
        assertEquals("b", ds.get(0).get("column"));
    }

    @Test
    void columnBfs_cycleDedup() {
        // a -> b -> a 环, 不应无限循环, 每节点只收一次
        List<DnLineageEdge> es = new ArrayList<>();
        es.add(col("d", "t", "a", "d", "t", "b", "DIRECT"));
        es.add(col("d", "t", "b", "d", "t", "a", "DIRECT"));
        List<Map<String, Object>> ds = LineageEdgeService.computeColumnBfs(es, "d", "t", "a", true, 10);
        assertEquals(1, ds.size());   // 只到 b(a 是起点已访问)
        assertEquals("b", ds.get(0).get("column"));
    }

    @Test
    void columnBfs_nullEdges_empty() {
        assertTrue(LineageEdgeService.computeColumnBfs(null, "d", "t", "a", true, 10).isEmpty());
    }

    @Test
    void splitColKey_threeParts() {
        String[] p = LineageEdgeService.splitColKey("ods.t_order.amt");
        assertEquals("ods", p[0]);
        assertEquals("t_order", p[1]);
        assertEquals("amt", p[2]);
    }

    @Test
    void splitColKey_missingColumn() {
        String[] p = LineageEdgeService.splitColKey("ods.t");
        assertEquals("ods", p[0]);
        assertEquals("t", p[1]);
        assertEquals("", p[2]);
    }

    @Test
    void buildSubgraphFromKey_columnCenteredBidirectional() {
        // ods.t.a -> dwd.t.b -> ads.t.c, 以 dwd.t.b 为中心应同时取到上游 a 与下游 c
        List<LineageEdgeService.TableEdge> es = new ArrayList<>();
        es.add(new LineageEdgeService.TableEdge("ods.t.a", "dwd.t.b", "COLUMN", "SQL"));
        es.add(new LineageEdgeService.TableEdge("dwd.t.b", "ads.t.c", "COLUMN", "SQL"));
        LineageEdgeService.SubGraph g = LineageEdgeService.buildSubgraphFromKey(es, "dwd.t.b", 3, 100);
        assertTrue(g.nodes.contains("ods.t.a"));
        assertTrue(g.nodes.contains("dwd.t.b"));
        assertTrue(g.nodes.contains("ads.t.c"));
        assertEquals(2, g.edges.size());
    }
}
