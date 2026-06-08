package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.IssueService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读工具：治理工单统计（总览/待办/超期 + 负责人排行）。值班排障看待办全貌。 */
@Component
@RequiredArgsConstructor
public class IssueStatsTool implements AiTool {

    private final IssueService issueService;

    @Override public String name() { return "issue_stats"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "查治理工单统计(开放/处理中/已关闭计数、超期等)与负责人排行榜。值班排障掌握待办全貌、督办用。无需参数。";
    }
    @Override public String paramsSchemaJson() { return "{}"; }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("stats", issueService.stats());
            out.put("leaderboard", issueService.leaderboard());
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
