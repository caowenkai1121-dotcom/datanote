package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.TaskDependencyService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 只读：解析 SQL 涉及的表清单。封装 TaskDependencyService.parseSQLTables。血缘/影响分析。 */
@Component
@RequiredArgsConstructor
public class SqlParseTablesTool implements AiTool {

    private final TaskDependencyService taskDependencyService;

    @Override public String name() { return "sql_parse_tables"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "解析一段 SQL 涉及的源表/目标表清单。用于血缘补全、变更影响分析。参数 sql 必填。";
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
            Set<String> tables = taskDependencyService.parseSQLTables(sql);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tables", tables == null ? new ArrayList<>() : new ArrayList<>(tables));
            out.put("count", tables == null ? 0 : tables.size());
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
