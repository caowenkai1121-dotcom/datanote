package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.consumption.MetricValueService;
import com.datanote.domain.consumption.model.DnMetricValue;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 工具: 计算一个指标的当前值(薄适配 MetricValueService.calc)。
 * 补 agent "能 create_metric 建指标却不能算值"的缺口; 跑完返回真实指标数值/业务日期/状态。
 */
@Component
@RequiredArgsConstructor
public class CalcMetricTool implements AiTool {

    private final MetricValueService metricValueService;

    @Override public String name() { return "calc_metric"; }
    @Override public String group() { return "metric"; }
    @Override public String description() {
        return "计算一个指标的当前值(写操作: 落计算记录, 需审批): 按 metricId 执行指标 SQL 算出数值, 返回 指标值/业务日期/状态。"
                + "用户说『算一下这个指标/这个指标现在多少/刷新指标值』且已有指标时用本工具(传 metricId); 没指标先 create_metric。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"metricId\":{\"type\":\"number\",\"required\":true,\"desc\":\"指标ID(create_metric 返回)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "metrics:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long metricId = AgentArgs.longVal(args, "metricId");
            if (metricId == null) return AiToolResult.fail("bad_arguments", "metricId 不能为空");
            String operator = ctx == null || ctx.getUserName() == null ? "AGENT" : ctx.getUserName();
            DnMetricValue v = metricValueService.calc(metricId, operator);
            if (v == null) return AiToolResult.fail("exec_failed", "计算返回空");
            if (v.getErrorMsg() != null && !v.getErrorMsg().isEmpty()) {
                return AiToolResult.fail("calc_failed", "指标 " + metricId + " 计算失败: " + v.getErrorMsg());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("metricId", metricId);
            out.put("metricCode", v.getMetricCode());
            out.put("metricValue", v.getMetricValue());
            out.put("valueText", v.getValueText());
            out.put("bizDate", v.getBizDate() == null ? null : v.getBizDate().toString());
            out.put("runStatus", v.getRunStatus());
            out.put("durationMs", v.getDurationMs());
            out.put("note", "指标值为真实计算结果, 据此回答勿臆造");
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
