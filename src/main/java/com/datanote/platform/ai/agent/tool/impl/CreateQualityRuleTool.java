package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import com.datanote.domain.governance.model.DnQualityRule;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 新建数据质量规则。映射 UI 同款 /quality/rule/save, 建成在治理健康-质量规则可见, 可调度执行。 */
@Component
@RequiredArgsConstructor
public class CreateQualityRuleTool implements AiTool {

    private final DnQualityRuleMapper ruleMapper;

    @Override public String name() { return "create_quality_rule"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "新建数据质量规则(写操作, 需人工审批)。把质量诉求落成可调度的检查规则。建成在治理健康-质量规则可见。"
                + "参数 ruleName、ruleType、datasourceId 必填; ruleType 合法枚举: null_check/unique_check/value_range/regex_check/custom_sql"
                + "(兼容别名 NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM_SQL, 会自动归一为小写词表); "
                + "databaseName/tableName/columnName/severity(HIGH/MEDIUM/LOW)/dimension/customSql/scheduleCron 可选。"
                + "需先用只读工具确认数据源ID存在。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"ruleName\":{\"type\":\"string\",\"required\":true,\"desc\":\"规则名\"},"
                + "\"ruleType\":{\"type\":\"string\",\"required\":true,\"desc\":\"规则类型(null_check/unique_check/value_range/regex_check/custom_sql)\"},"
                + "\"datasourceId\":{\"type\":\"number\",\"required\":true,\"desc\":\"数据源ID\"},"
                + "\"databaseName\":{\"type\":\"string\",\"required\":false},"
                + "\"tableName\":{\"type\":\"string\",\"required\":false},"
                + "\"columnName\":{\"type\":\"string\",\"required\":false},"
                + "\"severity\":{\"type\":\"string\",\"required\":false,\"desc\":\"HIGH/MEDIUM/LOW\"},"
                + "\"dimension\":{\"type\":\"string\",\"required\":false},"
                + "\"customSql\":{\"type\":\"string\",\"required\":false,\"desc\":\"CUSTOM_SQL 类型的检查SQL\"},"
                + "\"datasourceId\":{\"type\":\"number\",\"required\":false},"
                + "\"scheduleCron\":{\"type\":\"string\",\"required\":false}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "governance:quality"; }

    /** 归一化规则类型: 大写别名与合法小写值统一映射到库内小写词表, 未知返回 null */
    private static String normalizeRuleType(String t) {
        switch (t.trim().toUpperCase()) {
            case "NOT_NULL": case "NOTNULL": case "NULL_CHECK": return "null_check";
            case "UNIQUE": case "UNIQUE_CHECK": return "unique_check";
            case "RANGE": case "VALUE_RANGE": return "value_range";
            case "REGEX": case "REGEX_CHECK": return "regex_check";
            case "SQL": case "CUSTOM": case "CUSTOM_SQL": return "custom_sql";
            default: return null;
        }
    }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String ruleName = AgentArgs.str(args, "ruleName");
            String ruleType = AgentArgs.str(args, "ruleType");
            Long datasourceId = AgentArgs.longVal(args, "datasourceId");
            if (ruleName == null) return AiToolResult.fail("bad_arguments", "ruleName 不能为空");
            if (ruleType == null) return AiToolResult.fail("bad_arguments", "ruleType 不能为空");
            if (datasourceId == null) return AiToolResult.fail("bad_arguments", "datasourceId 不能为空(质量规则须绑定数据源)");
            // 词表归一: 大写别名/合法小写值统一映射到库内小写词表, 未知类型直接拒绝
            String normalizedType = normalizeRuleType(ruleType);
            if (normalizedType == null) {
                return AiToolResult.fail("bad_arguments", "ruleType 须为 null_check/unique_check/value_range/regex_check/custom_sql 之一");
            }
            DnQualityRule rule = new DnQualityRule();
            rule.setRuleName(ruleName);
            rule.setRuleType(normalizedType);
            rule.setDatasourceId(datasourceId);
            rule.setDatabaseName(AgentArgs.str(args, "databaseName"));
            rule.setTableName(AgentArgs.str(args, "tableName"));
            rule.setColumnName(AgentArgs.str(args, "columnName"));
            rule.setSeverity(AgentArgs.str(args, "severity"));
            rule.setDimension(AgentArgs.str(args, "dimension"));
            rule.setCustomSql(AgentArgs.str(args, "customSql"));
            rule.setScheduleCron(AgentArgs.str(args, "scheduleCron"));
            rule.setStatus(1);
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
            if (ctx != null && ctx.getUserName() != null) rule.setCreatedBy(ctx.getUserName());
            ruleMapper.insert(rule);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", rule);
            Map<String, Object> ctxMap = new LinkedHashMap<>();
            ctxMap.put("gov", "quality");
            out.put("_deeplink", AgentArgs.link("governance", ctxMap));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
