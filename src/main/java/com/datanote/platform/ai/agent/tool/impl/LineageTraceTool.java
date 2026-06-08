package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.LineageEdgeService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只读工具：上游溯源（补 lineage_impact 下游的反方向）。定位脏数据源头。结果附 _deeplink。 */
@Component
@RequiredArgsConstructor
public class LineageTraceTool implements AiTool {

    private final LineageEdgeService lineageEdgeService;

    @Override public String name() { return "lineage_trace"; }
    @Override public String group() { return "lineage"; }
    @Override public String description() {
        return "查一张表的上游溯源链路(沿血缘 BFS 列出它的数据来源表)。排查质量问题/定位脏数据源头时用。参数 db、table 必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = AgentArgs.str(args, "db");
            String table = AgentArgs.str(args, "table");
            if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
            List<Map<String, Object>> up = lineageEdgeService.trace(db, table);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("db", db);
            out.put("table", table);
            out.put("upstreamCount", up == null ? 0 : up.size());
            out.put("upstream", up);
            out.put("_deeplink", AgentArgs.lineageLink(db, table));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
