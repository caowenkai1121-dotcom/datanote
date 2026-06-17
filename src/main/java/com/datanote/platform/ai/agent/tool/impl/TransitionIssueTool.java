package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.common.exception.BusinessException;
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

/** 写工具(MEDIUM): 流转治理工单状态。 */
@Component
@RequiredArgsConstructor
public class TransitionIssueTool implements AiTool {

    private final IssueService issueService;

    @Override public String name() { return "transition_issue"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "流转治理工单状态(状态机校验: OPEN→FIXING/CLOSED, FIXING→RESOLVED/OPEN, RESOLVED→VERIFIED/FIXING, VERIFIED→CLOSED/FIXING, CLOSED→OPEN)。质量类工单 RESOLVED/CLOSED 前会自动复检, 未达标拦截。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"id\":{\"type\":\"number\",\"required\":true,\"desc\":\"工单ID\"}," +
               "\"toStatus\":{\"type\":\"string\",\"required\":true,\"desc\":\"目标状态: OPEN/FIXING/RESOLVED/VERIFIED/CLOSED\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "governance:issue"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long id = AgentArgs.longVal(args, "id");
            if (id == null) return AiToolResult.fail("bad_arguments", "id 不能为空");
            String toStatus = AgentArgs.str(args, "toStatus");
            if (toStatus == null) return AiToolResult.fail("bad_arguments", "toStatus 不能为空");
            String operator = ctx != null ? ctx.getUserName() : null;
            issueService.transition(id, toStatus, operator);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("transitioned", true);
            out.put("id", id);
            out.put("toStatus", toStatus);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (BusinessException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
