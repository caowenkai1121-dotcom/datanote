package com.datanote.domain.orchestration;

import com.datanote.domain.orchestration.model.DnLineageEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表级血缘 BFS 纯函数单测 —— 一次性加载边后内存多跳(下游影响/上游溯源/去重防环/深度限制)。
 */
class TableLineageBfsTest {

    private DnLineageEdge tbl(String sdb, String stab, String ddb, String dtab) {
        DnLineageEdge e = new DnLineageEdge();
        e.setLevelType("TABLE");
        e.setSrcDb(sdb); e.setSrcTable(stab);
        e.setDstDb(ddb); e.setDstTable(dtab);
        e.setSource("SQL");
        return e;
    }

    // ods.t -> dwd.t -> ads.t  (单链)
    private List<DnLineageEdge> chain() {
        List<DnLineageEdge> es = new ArrayList<>();
        es.add(tbl("ods", "t", "dwd", "t"));
        es.add(tbl("dwd", "t", "ads", "t"));
        return es;
    }

    @Test
    void impact_downstreamMultiHop() {
        List<Map<String, Object>> ds = LineageEdgeService.computeTableBfs(chain(), "ods", "t", true, 10);
        assertEquals(2, ds.size());
        assertEquals("dwd", ds.get(0).get("db"));
        assertEquals(1, ds.get(0).get("depth"));
        assertEquals("ads", ds.get(1).get("db"));
        assertEquals(2, ds.get(1).get("depth"));
    }

    @Test
    void trace_upstreamMultiHop() {
        List<Map<String, Object>> us = LineageEdgeService.computeTableBfs(chain(), "ads", "t", false, 10);
        assertEquals(2, us.size());
        assertEquals("dwd", us.get(0).get("db"));
        assertEquals("ods", us.get(1).get("db"));
    }

    @Test
    void depthLimit() {
        List<Map<String, Object>> ds = LineageEdgeService.computeTableBfs(chain(), "ods", "t", true, 1);
        assertEquals(1, ds.size());
        assertEquals("dwd", ds.get(0).get("db"));
    }

    @Test
    void cycleDedup() {
        List<DnLineageEdge> es = new ArrayList<>();
        es.add(tbl("d", "a", "d", "b"));
        es.add(tbl("d", "b", "d", "a"));
        List<Map<String, Object>> ds = LineageEdgeService.computeTableBfs(es, "d", "a", true, 10);
        assertEquals(1, ds.size());   // 只到 b(a 起点已访问)
        assertEquals("b", ds.get(0).get("table"));
    }

    @Test
    void nullEdges_empty() {
        assertTrue(LineageEdgeService.computeTableBfs(null, "d", "t", true, 10).isEmpty());
    }
}
