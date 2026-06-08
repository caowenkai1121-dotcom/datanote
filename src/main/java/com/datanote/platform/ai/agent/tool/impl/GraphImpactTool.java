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

/** graph_impact：图库多跳下游影响面（Cypher FLOWS_TO*），图不可用自动回退 Java BFS。永返 ok(带 engine 标记)。 */
@Component
@RequiredArgsConstructor
public class GraphImpactTool implements AiTool {

    private final GraphStoreClient graph;
    private final LineageEdgeService lineageEdgeService;

    @Override public String name() { return "graph_impact"; }
    @Override public String group() { return "lineage"; }
    @Override public String description() {
        return "查一张表的下游影响面(图数据库多跳遍历, 比 lineage_impact 更深更快)。下线/改表前评估爆炸半径。参数 db、table 必填, depth 可选(默认5,上限10)。图库不可用时自动回退 Java BFS。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"},\"depth\":{\"type\":\"number\",\"required\":false,\"desc\":\"最大跳数,默认5\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String db = AgentArgs.str(args, "db");
        String table = AgentArgs.str(args, "table");
        if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
        int depth = clamp(AgentArgs.intVal(args, "depth", 5), 1, 10);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db);
        out.put("table", table);

        // 图优先
        if (graph.available()) {
            try {
                String cy = "MATCH (s:Table {fqn:$fqn})-[:FLOWS_TO*1.." + depth + "]->(d:Table) "
                        + "RETURN DISTINCT d.fqn AS fqn, d.db AS db, d.name AS name LIMIT 500";
                List<Map<String, Object>> rows = graph.run(cy, Collections.singletonMap("fqn", db + "." + table));
                if (rows != null) {
                    out.put("engine", "graph");
                    out.put("depth", depth);
                    out.put("downstreamCount", rows.size());
                    out.put("downstream", rows);
                    out.put("_deeplink", AgentArgs.lineageLink(db, table));
                    return AiToolResult.ok(out);
                }
            } catch (Exception ignore) {
                // 落入回退
            }
        }
        // 回退 Java BFS(既有 lineage_impact 同源)
        List<Map<String, Object>> impact = lineageEdgeService.impact(db, table);
        out.put("engine", "bfs_fallback");
        out.put("downstreamCount", impact == null ? 0 : impact.size());
        out.put("downstream", impact);
        out.put("_deeplink", AgentArgs.lineageLink(db, table));
        return AiToolResult.ok(out);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
