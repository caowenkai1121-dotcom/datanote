package com.datanote.service;

import com.datanote.service.LineageEdgeService.SubGraph;
import com.datanote.service.LineageEdgeService.TableEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** buildSubgraph 纯函数单测（不碰数据库）。 */
class LineageGraphTest {

    private TableEdge e(String src, String dst) {
        return new TableEdge(src, dst, "TABLE", "MAPPING");
    }

    @Test
    void chainDepthOneFromMiddle() {
        // A -> B -> C，起点 B，depth=1 应拿到 A、B、C 与 2 条边
        List<TableEdge> edges = new ArrayList<>();
        edges.add(e("db.A", "db.B"));
        edges.add(e("db.B", "db.C"));

        SubGraph g = LineageEdgeService.buildSubgraph(edges, "db", "B", 1, 100);

        assertEquals(3, g.nodes.size(), "应含 A、B、C");
        assertTrue(g.nodes.contains("db.A"));
        assertTrue(g.nodes.contains("db.B"));
        assertTrue(g.nodes.contains("db.C"));
        assertEquals(2, g.edges.size(), "应有 2 条边");
    }

    @Test
    void depthZeroOnlyStart() {
        List<TableEdge> edges = new ArrayList<>();
        edges.add(e("db.A", "db.B"));
        SubGraph g = LineageEdgeService.buildSubgraph(edges, "db", "A", 0, 100);
        assertEquals(1, g.nodes.size());
        assertTrue(g.nodes.contains("db.A"));
        assertEquals(0, g.edges.size());
    }

    @Test
    void cycleDoesNotLoopForever() {
        // A -> B -> A 环
        List<TableEdge> edges = new ArrayList<>();
        edges.add(e("db.A", "db.B"));
        edges.add(e("db.B", "db.A"));
        SubGraph g = LineageEdgeService.buildSubgraph(edges, "db", "A", 3, 100);
        assertEquals(2, g.nodes.size(), "只有 A、B 两个节点");
        assertEquals(2, g.edges.size(), "两条边各保留一次（去重）");
    }

    @Test
    void maxNodesTruncates() {
        // 星型：A -> B1..B5，maxNodes=3 应截断
        List<TableEdge> edges = new ArrayList<>();
        for (int i = 1; i <= 5; i++) edges.add(e("db.A", "db.B" + i));
        SubGraph g = LineageEdgeService.buildSubgraph(edges, "db", "A", 2, 3);
        assertTrue(g.nodes.size() <= 3, "节点数不超过上限 3，实际=" + g.nodes.size());
        assertTrue(g.nodes.contains("db.A"), "起点必含");
    }

    @Test
    void isolatedStartHasNoEdges() {
        List<TableEdge> edges = new ArrayList<>();
        edges.add(e("db.X", "db.Y")); // 与起点无关
        SubGraph g = LineageEdgeService.buildSubgraph(edges, "db", "Z", 2, 100);
        assertEquals(1, g.nodes.size());
        assertTrue(g.nodes.contains("db.Z"));
        assertEquals(0, g.edges.size());
    }

    @Test
    void selfLoopDedupedAndNoExtraNode() {
        List<TableEdge> edges = new ArrayList<>();
        edges.add(e("db.A", "db.A")); // 自环
        edges.add(e("db.A", "db.A")); // 重复
        SubGraph g = LineageEdgeService.buildSubgraph(edges, "db", "A", 2, 100);
        assertEquals(1, g.nodes.size());
        assertEquals(1, g.edges.size(), "自环去重后保留 1 条");
        assertFalse(g.edges.isEmpty());
    }
}
