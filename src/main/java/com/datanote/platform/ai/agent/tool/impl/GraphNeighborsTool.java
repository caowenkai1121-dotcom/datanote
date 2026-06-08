package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.LineageEdgeService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.datanote.platform.ai.graph.GraphStoreClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** graph_neighbors：以表为中心的 N 跳双向血缘子图(节点+边)。图不可用回退既有 graph()。永返 ok。 */
@Component
@RequiredArgsConstructor
public class GraphNeighborsTool implements AiTool {

    private final GraphStoreClient graph;
    private final LineageEdgeService lineageEdgeService;

    @Override public String name() { return "graph_neighbors"; }
    @Override public String group() { return "lineage"; }
    @Override public String description() {
        return "取以一张表为中心的 N 跳双向血缘子图(上下游节点与边)。整体把握一张表的血缘网络结构。参数 db、table 必填, depth 可选(默认2,上限5)。图库不可用回退既有内存构图。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"},\"depth\":{\"type\":\"number\",\"required\":false,\"desc\":\"跳数,默认2\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String db = AgentArgs.str(args, "db");
        String table = AgentArgs.str(args, "table");
        if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
        int depth = clamp(AgentArgs.intVal(args, "depth", 2), 1, 5);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db);
        out.put("table", table);

        if (graph.available()) {
            try {
                String cy = "MATCH p=(s:Table {fqn:$fqn})-[:FLOWS_TO*1.." + depth + "]-(n:Table) "
                        + "WITH nodes(p) AS ns, relationships(p) AS rs UNWIND rs AS r "
                        + "RETURN DISTINCT startNode(r).fqn AS src, endNode(r).fqn AS dst LIMIT 800";
                List<Map<String, Object>> edges = graph.run(cy, Collections.singletonMap("fqn", db + "." + table));
                if (edges != null) {
                    out.put("engine", "graph");
                    out.put("depth", depth);
                    out.put("edgeCount", edges.size());
                    out.put("edges", edges);
                    out.put("_deeplink", AgentArgs.lineageLink(db, table));
                    return AiToolResult.ok(out);
                }
            } catch (Exception ignore) {
            }
        }
        Map<String, Object> g = lineageEdgeService.graph(db, table, depth);
        out.put("engine", "bfs_fallback");
        out.put("graph", g);
        out.put("_deeplink", AgentArgs.lineageLink(db, table));
        return AiToolResult.ok(out);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
