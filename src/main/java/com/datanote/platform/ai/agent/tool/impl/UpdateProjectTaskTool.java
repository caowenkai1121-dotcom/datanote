package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectTaskService;
import com.datanote.domain.project.model.DnProjectTask;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 写工具(MEDIUM): 创建/更新项目任务。
 * 调用 saveTaskAndSync: 任务首次置 DONE 且关联 GOV_ISSUE 时自动推进工单; 归档项目拒写。
 */
@Component
@RequiredArgsConstructor
public class UpdateProjectTaskTool implements AiTool {

    private final ProjectTaskService projectTaskService;

    @Override public String name() { return "update_project_task"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "创建/更新项目任务(更新传 id; status 变 DONE 且关联治理工单时会自动推进工单; 归档项目拒写)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"}," +
               "\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"任务标题\"}," +
               "\"id\":{\"type\":\"number\",\"required\":false,\"desc\":\"任务ID(更新时传)\"}," +
               "\"status\":{\"type\":\"string\",\"required\":false,\"desc\":\"TODO/DOING/DONE\"}," +
               "\"assignee\":{\"type\":\"string\",\"required\":false,\"desc\":\"指派人用户名\"}," +
               "\"priority\":{\"type\":\"string\",\"required\":false,\"desc\":\"HIGH/MEDIUM/LOW\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "project:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            String title = AgentArgs.str(args, "title");
            if (title == null) return AiToolResult.fail("bad_arguments", "title 不能为空");
            DnProjectTask t = new DnProjectTask();
            t.setId(AgentArgs.longVal(args, "id"));
            t.setTitle(title);
            t.setStatus(AgentArgs.str(args, "status"));
            t.setAssignee(AgentArgs.str(args, "assignee"));
            t.setPriority(AgentArgs.str(args, "priority"));
            Map<String, Object> result = projectTaskService.saveTaskAndSync(projectId, t);
            return AiToolResult.ok(result);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
