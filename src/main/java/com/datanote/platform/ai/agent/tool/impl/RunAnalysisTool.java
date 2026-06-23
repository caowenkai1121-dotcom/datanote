package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.SqlMaskRewriter;
import com.datanote.platform.ai.agent.analysis.AnalysisQueryService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * run_analysis：只读聚合分析查询。
 * 单条 SELECT/WITH 语句，自动限 2000 行 / 30s 超时；
 * 受发起人数据权限(ACL)与脱敏策略(fail-closed)双重约束。
 * 结果以 _preview 数据表格形式在前端展示，可配合 chart 出图。
 */
@Component
@RequiredArgsConstructor
public class RunAnalysisTool implements AiTool {

    private final AnalysisQueryService analysisQueryService;

    @Override public String name() { return "run_analysis"; }
    @Override public String group() { return "analysis"; }
    @Override public String description() {
        return "运行只读分析查询(仅 SELECT/WITH 单语句; 结果自动限 2000 行/30s 超时, 无 LIMIT 会自动补 LIMIT 2000——需精确总量请用 COUNT/聚合而非数行; 受发起人数据权限与脱敏策略约束)。"
                + "db 指定库(默认数仓Doris)。用于聚合统计后配合 chart 出图。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"sql\":{\"type\":\"string\",\"required\":true,\"desc\":\"只读 SELECT/WITH 单语句\"},"
                + "\"db\":{\"type\":\"string\",\"required\":false,\"desc\":\"目标库名, 默认数仓Doris\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "catalog:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String sql = AgentArgs.str(args, "sql");
        if (sql == null) {
            return AiToolResult.fail("bad_arguments", "sql 不能为空");
        }
        String db = AgentArgs.str(args, "db"); // null = 使用数仓 Doris

        Map<String, Object> result;
        try {
            result = analysisQueryService.runSelect(sql, db, ctx);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (SecurityException e) {
            return AiToolResult.fail("data_denied", e.getMessage());
        } catch (SqlMaskRewriter.MaskRewriteException e) {
            return AiToolResult.fail("data_denied", "命中脱敏策略, 该数据不可直接分析查询: " + e.getMessage());
        } catch (SQLException e) {
            return AiToolResult.fail("exec_failed", "SQL 执行失败: " + e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_preview", result);
        out.put("db", db);
        out.put("returned", result.get("returned"));
        return AiToolResult.ok(out);
    }
}
