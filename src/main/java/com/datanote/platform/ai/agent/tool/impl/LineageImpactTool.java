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

/** M1 只读工具：下游影响面。封装 LineageEdgeService.impact(db,table)（下游 BFS）。下线/删表评估前必跑。 */
@Component
@RequiredArgsConstructor
public class LineageImpactTool implements AiTool {

    private final LineageEdgeService lineageEdgeService;

    @Override public String name() { return "lineage_impact"; }
    @Override public String group() { return "lineage"; }
    @Override public String description() {
        return "查一张表的下游影响面(沿表级血缘 BFS 列出受其变更影响的所有下游表)。下线/删表/改表结构前必跑,评估爆炸半径。参数 db(库名)、table(表名)必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = (args != null && args.hasNonNull("db")) ? args.get("db").asText() : null;
            String table = (args != null && args.hasNonNull("table")) ? args.get("table").asText() : null;
            if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) {
                return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
            }
            List<Map<String, Object>> impact = lineageEdgeService.impact(db.trim(), table.trim());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("db", db.trim());
            out.put("table", table.trim());
            out.put("downstreamCount", impact == null ? 0 : impact.size());
            out.put("downstream", impact);
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
