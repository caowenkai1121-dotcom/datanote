package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectReleaseService;
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
 * 写工具(HIGH): 审批通过项目发布。
 * 审批人身份由服务端通过 ProjectService.currentUser() 自动捕获, 禁止从 args 传入。
 * 服务层守卫: 需 release:approve 角色; 多人项目禁自批。
 */
@Component
@RequiredArgsConstructor
public class ApproveProjectReleaseTool implements AiTool {

    private final ProjectReleaseService projectReleaseService;

    @Override public String name() { return "approve_project_release"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "审批通过项目发布。多人项目禁止自批(服务层守卫)。高风险写操作, 每次需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"releaseId\":{\"type\":\"number\",\"required\":true,\"desc\":\"发布版本ID\"}," +
               "\"comment\":{\"type\":\"string\",\"required\":false,\"desc\":\"审批意见\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }
    @Override public String requiredPerm() { return "project:approve"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long releaseId = AgentArgs.longOrCtx(args, "releaseId", ctx);
            if (releaseId == null) return AiToolResult.fail("bad_arguments", "releaseId 不能为空");
            String comment = AgentArgs.str(args, "comment");
            // approver 身份由服务端 ProjectService.currentUser() 自动捕获, 不从 args 传入
            projectReleaseService.approve(releaseId, comment);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("approved", true);
            out.put("releaseId", releaseId);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            // BusinessException / 自批被拦 等统一归 conflict
            String msg = e.getMessage();
            if (msg != null && (msg.contains("自批") || msg.contains("审批权限") || msg.contains("状态"))) {
                return AiToolResult.fail("conflict", msg);
            }
            return AiToolResult.fail("exec_failed", msg);
        }
    }
}
