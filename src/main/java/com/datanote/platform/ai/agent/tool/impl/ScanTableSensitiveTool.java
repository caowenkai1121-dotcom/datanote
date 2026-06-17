package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.ClassificationService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读工具: 扫描某表敏感字段候选, 不落库。 */
@Component
@RequiredArgsConstructor
public class ScanTableSensitiveTool implements AiTool {

    private final ClassificationService classificationService;

    @Override public String name() { return "scan_table_sensitive"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "扫描某表的敏感字段候选(只读, 不落库): 返回每列建议的敏感类型/分级/置信度。供后续 classify_column 确认。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"}," +
               "\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "governance:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = AgentArgs.str(args, "db");
            if (db == null) return AiToolResult.fail("bad_arguments", "db 不能为空");
            String table = AgentArgs.str(args, "table");
            if (table == null) return AiToolResult.fail("bad_arguments", "table 不能为空");
            List<Map<String, Object>> candidates = classificationService.scanTable(db, table);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("candidates", candidates);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
