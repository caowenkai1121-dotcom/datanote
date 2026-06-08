package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.HealthScoreService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读工具：治理健康分趋势（近 N 天）。解读健康分变化与短板维度。 */
@Component
@RequiredArgsConstructor
public class HealthTrendTool implements AiTool {

    private final HealthScoreService healthScoreService;

    @Override public String name() { return "health_trend"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "查治理健康分近 N 天趋势(总分及五维)。解读健康度变化、定位掉分维度。参数 days 可选(默认30)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"days\":{\"type\":\"number\",\"required\":false,\"desc\":\"天数,默认30\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            int days = AgentArgs.intVal(args, "days", 30);
            if (days < 1) days = 30;
            if (days > 365) days = 365;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", days);
            out.put("trend", healthScoreService.trend(days));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
