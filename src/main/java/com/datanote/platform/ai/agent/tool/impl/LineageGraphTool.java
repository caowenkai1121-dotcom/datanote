package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.LineageEdgeService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读工具：以表为中心的 N 跳血缘子图（节点+边）。整体理解一张表的血缘网络。结果附 _deeplink。 */
@Component
@RequiredArgsConstructor
public class LineageGraphTool implements AiTool {

    private final LineageEdgeService lineageEdgeService;

    @Override public String name() { return "lineage_graph"; }
    @Override public String group() { return "lineage"; }
    @Override public String description() {
        return "以一张表为中心取 N 跳血缘子图(双向, 含节点与边)。整体把握一张表的上下游血缘网络。参数 db、table 必填, depth 可选(默认2, 上限6)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"},\"depth\":{\"type\":\"number\",\"required\":false,\"desc\":\"跳数,默认2\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = AgentArgs.str(args, "db");
            String table = AgentArgs.str(args, "table");
            if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
            int depth = AgentArgs.intVal(args, "depth", 2);
            Map<String, Object> graph = lineageEdgeService.graph(db, table, depth);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("graph", graph);
            out.put("_deeplink", AgentArgs.lineageLink(db, table));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
