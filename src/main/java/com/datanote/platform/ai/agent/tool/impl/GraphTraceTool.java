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

/** graph_trace：图库多跳上游溯源（Cypher 逆向 FLOWS_TO*），图不可用回退 Java BFS。永返 ok。 */
@Component
@RequiredArgsConstructor
public class GraphTraceTool implements AiTool {

    private final GraphStoreClient graph;
    private final LineageEdgeService lineageEdgeService;

    @Override public String name() { return "graph_trace"; }
    @Override public String group() { return "lineage"; }
    @Override public String description() {
        return "查一张表的上游溯源链路(图数据库多跳逆向遍历)。报表数据有误时逆向定位上游脏数据源头。参数 db、table 必填, depth 可选(默认5,上限10)。图库不可用自动回退 Java BFS。";
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

        if (graph.available()) {
            try {
                String cy = "MATCH (s:Table {fqn:$fqn})<-[:FLOWS_TO*1.." + depth + "]-(u:Table) "
                        + "RETURN DISTINCT u.fqn AS fqn, u.db AS db, u.name AS name LIMIT 500";
                List<Map<String, Object>> rows = graph.run(cy, Collections.singletonMap("fqn", db + "." + table));
                if (rows != null) {
                    out.put("engine", "graph");
                    out.put("depth", depth);
                    out.put("upstreamCount", rows.size());
                    out.put("upstream", rows);
                    out.put("_deeplink", AgentArgs.lineageLink(db, table));
                    return AiToolResult.ok(out);
                }
            } catch (Exception ignore) {
            }
        }
        List<Map<String, Object>> trace = lineageEdgeService.trace(db, table);
        out.put("engine", "bfs_fallback");
        out.put("upstreamCount", trace == null ? 0 : trace.size());
        out.put("upstream", trace);
        out.put("_deeplink", AgentArgs.lineageLink(db, table));
        return AiToolResult.ok(out);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
