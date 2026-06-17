package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectMemberService;
import com.datanote.domain.project.model.DnProjectMember;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 给项目添加成员。服务层校验用户存在、角色合法、不重复。 */
@Component
@RequiredArgsConstructor
public class AddProjectMemberTool implements AiTool {

    private final ProjectMemberService projectMemberService;

    @Override public String name() { return "add_project_member"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "给项目添加成员(username 须为系统已有用户; role: OWNER/ADMIN/DEVELOPER/OPS/VIEWER)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"}," +
               "\"username\":{\"type\":\"string\",\"required\":true,\"desc\":\"系统已有用户名\"}," +
               "\"role\":{\"type\":\"string\",\"required\":true,\"desc\":\"项目角色: OWNER/ADMIN/DEVELOPER/OPS/VIEWER\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "project:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            String username = AgentArgs.str(args, "username");
            if (username == null) return AiToolResult.fail("bad_arguments", "username 不能为空");
            String role = AgentArgs.str(args, "role");
            if (role == null) return AiToolResult.fail("bad_arguments", "role 不能为空");
            DnProjectMember added = projectMemberService.add(projectId, username, role.toUpperCase());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("added", true);
            out.put("projectId", projectId);
            out.put("username", added.getUsername());
            out.put("role", added.getProjectRole());
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
