package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.OverviewService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** M1 只读工具：治理总览。薄适配器封装 OverviewService.overview()。 */
@Component
@RequiredArgsConstructor
public class GovOverviewTool implements AiTool {

    private final OverviewService overviewService;

    @Override public String name() { return "gov_overview"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "一次拉全治理总览(健康分/资产数/质量近期通过率/工单open-fixing-closed/敏感分布)。早晨值班排障、了解全局健康度的入口。无需参数。";
    }
    @Override public String paramsSchemaJson() { return "{}"; }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            return AiToolResult.ok(overviewService.overview());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
