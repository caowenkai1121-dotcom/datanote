package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：给一段 SQL 提优化建议(性能/写法)。封装 AiAssistService.optimizeSql。 */
@Component
@RequiredArgsConstructor
public class SqlOptimizeTool implements AiTool {

    private final AiAssistService aiAssistService;

    @Override public String name() { return "sql_optimize"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "给一段 SQL 提性能/写法优化建议。SQL 跑得慢或想改进时用。参数 sql 必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"sql\":{\"type\":\"string\",\"required\":true,\"desc\":\"SQL 语句\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String sql = AgentArgs.strOrCtx(args, "sql", ctx);
            if (sql == null) sql = AgentArgs.strOrCtx(args, "currentSql", ctx);
            if (sql == null) return AiToolResult.fail("bad_arguments", "sql 不能为空");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("optimization", aiAssistService.optimizeSql(sql));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
