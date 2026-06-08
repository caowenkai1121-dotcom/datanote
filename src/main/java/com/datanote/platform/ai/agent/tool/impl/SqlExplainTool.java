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

/** 只读：解释一段 SQL 的语义与逻辑。封装 AiAssistService.explainSql。开发态读 SQL 理解。 */
@Component
@RequiredArgsConstructor
public class SqlExplainTool implements AiTool {

    private final AiAssistService aiAssistService;

    @Override public String name() { return "sql_explain"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "解释一段 SQL 的语义、逻辑与产出。读懂他人 SQL/复杂查询时用。参数 sql 必填。";
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
            out.put("explanation", aiAssistService.explainSql(sql));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
