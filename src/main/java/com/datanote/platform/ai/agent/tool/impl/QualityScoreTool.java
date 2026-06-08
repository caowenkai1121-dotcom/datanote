package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.HealthScoreService;
import com.datanote.domain.governance.QualityService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** M1 只读工具：质量健康分 + 质量根因分析。封装 HealthScoreService.current() 与 QualityService.failureAnalysis(ruleId)。 */
@Component
@RequiredArgsConstructor
public class QualityScoreTool implements AiTool {

    private final HealthScoreService healthScoreService;
    private final QualityService qualityService;

    @Override public String name() { return "quality_score"; }
    @Override public String group() { return "quality"; }
    @Override public String description() {
        return "查全局数据健康总分与五维分(质量/血缘/规范/安全/生命周期),并附质量执行根因分析(状态分布+最近失败样本)。用于定位质量骤降。可选参数 ruleId 聚焦单条质量规则,省略则给全局。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"ruleId\":{\"type\":\"number\",\"required\":false,\"desc\":\"质量规则ID,省略=全局\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("health", healthScoreService.current());
            Long ruleId = (args != null && args.hasNonNull("ruleId")) ? args.get("ruleId").asLong() : null;
            out.put("failureAnalysis", qualityService.failureAnalysis(ruleId));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
