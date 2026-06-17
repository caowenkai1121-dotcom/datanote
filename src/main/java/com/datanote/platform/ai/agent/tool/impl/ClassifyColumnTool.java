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
import java.util.Map;

/** 写工具(MEDIUM): 确认/设置字段敏感分级与类型。 */
@Component
@RequiredArgsConstructor
public class ClassifyColumnTool implements AiTool {

    private final ClassificationService classificationService;

    @Override public String name() { return "classify_column"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "确认/设置某字段的敏感分级与类型(写 dn_column_meta + 审计留痕)。先用 scan_table_sensitive 取候选再确认。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"}," +
               "\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"}," +
               "\"column\":{\"type\":\"string\",\"required\":true,\"desc\":\"列名\"}," +
               "\"newLevel\":{\"type\":\"string\",\"required\":true,\"desc\":\"密级代码(须为 dn_classification_level 中已定义的合法值)\"}," +
               "\"sensitiveType\":{\"type\":\"string\",\"required\":false,\"desc\":\"敏感类型(可选)\"}," +
               "\"reason\":{\"type\":\"string\",\"required\":false,\"desc\":\"操作原因(可选)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "governance:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = AgentArgs.str(args, "db");
            if (db == null) return AiToolResult.fail("bad_arguments", "db 不能为空");
            String table = AgentArgs.str(args, "table");
            if (table == null) return AiToolResult.fail("bad_arguments", "table 不能为空");
            String column = AgentArgs.str(args, "column");
            if (column == null) return AiToolResult.fail("bad_arguments", "column 不能为空");
            String newLevel = AgentArgs.str(args, "newLevel");
            if (newLevel == null) return AiToolResult.fail("bad_arguments", "newLevel 不能为空");
            String sensitiveType = AgentArgs.str(args, "sensitiveType");
            String reason = AgentArgs.str(args, "reason");
            String operator = ctx != null ? ctx.getUserName() : null;
            // confirm 返回 void; IllegalArgumentException/BusinessException 由 service 内部 validateLevel 等抛出
            classificationService.confirm(db, table, column, newLevel, sensitiveType, operator, reason);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("classified", true);
            out.put("db", db);
            out.put("table", table);
            out.put("column", column);
            out.put("newLevel", newLevel);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
