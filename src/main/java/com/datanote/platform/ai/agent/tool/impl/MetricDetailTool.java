package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.consumption.MetricValueService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：指标详情(最新值+历史趋势+输入数据质量)。封装 MetricValueService.latest/history/inputQuality。带 _deeplink 回指标管理。 */
@Component
@RequiredArgsConstructor
public class MetricDetailTool implements AiTool {

    private final MetricValueService metricValueService;

    @Override public String name() { return "metric_detail"; }
    @Override public String group() { return "metrics"; }
    @Override public String description() {
        return "查指标的最新值 + 历史趋势 + 输入数据质量(可信度)。解读指标、分析异常波动来源。参数 metricId 必填, historyLimit 可选(默认10)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"metricId\":{\"type\":\"number\",\"required\":true,\"desc\":\"指标ID\"},\"historyLimit\":{\"type\":\"number\",\"required\":false,\"desc\":\"历史条数,默认10\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long metricId = AgentArgs.longOrCtx(args, "metricId", ctx);
            if (metricId == null) return AiToolResult.fail("bad_arguments", "metricId 不能为空");
            int limit = AgentArgs.intVal(args, "historyLimit", 10);
            if (limit < 1) limit = 10;
            if (limit > 100) limit = 100;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("latest", metricValueService.latest(metricId));
            out.put("history", metricValueService.history(metricId, limit));
            out.put("inputQuality", metricValueService.inputQuality(metricId));
            out.put("_deeplink", AgentArgs.metricLink(metricId));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
