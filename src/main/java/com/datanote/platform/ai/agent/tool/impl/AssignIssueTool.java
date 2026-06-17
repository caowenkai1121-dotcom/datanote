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

/** 写工具(LOW): 把治理工单指派给某用户。 */
@Component
@RequiredArgsConstructor
public class AssignIssueTool implements AiTool {

    private final IssueService issueService;

    @Override public String name() { return "assign_issue"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "把治理工单指派给某用户(指派后给被指派人发铃铛通知)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"id\":{\"type\":\"number\",\"required\":true,\"desc\":\"工单ID\"}," +
               "\"owner\":{\"type\":\"string\",\"required\":true,\"desc\":\"被指派的用户名\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "governance:issue"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long id = AgentArgs.longVal(args, "id");
            if (id == null) return AiToolResult.fail("bad_arguments", "id 不能为空");
            String owner = AgentArgs.str(args, "owner");
            if (owner == null) return AiToolResult.fail("bad_arguments", "owner 不能为空");
            issueService.assign(id, owner);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assigned", true);
            out.put("id", id);
            out.put("owner", owner);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
