package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.BaselineCheckService;
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

@Component
@RequiredArgsConstructor
public class BaselineStatusTool implements AiTool {

    private final BaselineCheckService baselineCheckService;

    @Override public String name() { return "baseline_status"; }
    @Override public String group() { return "ops"; }
    @Override public String description() {
        return "查看今日基线(baseline)达成状态: 各基线 met/broken/pending + 未达任务明细。只读。";
    }
    @Override public String paramsSchemaJson() { return "{}"; }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return null; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            List<Map<String, Object>> status = baselineCheckService.statusToday();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("baseline", status);
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
