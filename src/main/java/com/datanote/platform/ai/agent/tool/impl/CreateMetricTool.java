package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
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

/** 写工具(MEDIUM): 新建业务指标定义。映射 UI 同款 /metric/save, 建成在指标管理可见。 */
@Component
@RequiredArgsConstructor
public class CreateMetricTool implements AiTool {

    private final DnMetricMapper metricMapper;

    @Override public String name() { return "create_metric"; }
    @Override public String group() { return "metric"; }
    @Override public String description() {
        return "新建业务指标定义(写操作, 需人工审批)。把业务口径落成统一指标。建成在指标管理可见。"
                + "参数 metricName 必填; metricCode/category/description/calcFormula/dimensions/unit 可选。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"metricName\":{\"type\":\"string\",\"required\":true,\"desc\":\"指标名\"},"
                + "\"metricCode\":{\"type\":\"string\",\"required\":false,\"desc\":\"指标编码\"},"
                + "\"category\":{\"type\":\"string\",\"required\":false,\"desc\":\"分类\"},"
                + "\"description\":{\"type\":\"string\",\"required\":false},"
                + "\"calcFormula\":{\"type\":\"string\",\"required\":false,\"desc\":\"计算口径/公式\"},"
                + "\"dimensions\":{\"type\":\"string\",\"required\":false,\"desc\":\"分析维度\"},"
                + "\"unit\":{\"type\":\"string\",\"required\":false,\"desc\":\"单位\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "metrics:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String metricName = AgentArgs.str(args, "metricName");
            if (metricName == null) return AiToolResult.fail("bad_arguments", "metricName 不能为空");
            DnMetric metric = new DnMetric();
            metric.setMetricName(metricName);
            metric.setMetricCode(AgentArgs.str(args, "metricCode"));
            metric.setCategory(AgentArgs.str(args, "category"));
            metric.setDescription(AgentArgs.str(args, "description"));
            metric.setCalcFormula(AgentArgs.str(args, "calcFormula"));
            metric.setDimensions(AgentArgs.str(args, "dimensions"));
            metric.setUnit(AgentArgs.str(args, "unit"));
            metric.setStatus(1);
            metric.setCreatedAt(LocalDateTime.now());
            metric.setUpdatedAt(LocalDateTime.now());
            if (ctx != null && ctx.getUserName() != null) metric.setOwner(ctx.getUserName());
            metricMapper.insert(metric);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", metric);
            out.put("_deeplink", AgentArgs.metricLink(metric.getId()));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
