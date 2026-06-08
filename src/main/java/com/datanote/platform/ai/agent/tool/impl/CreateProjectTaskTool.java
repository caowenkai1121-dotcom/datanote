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

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 在项目下建任务。映射 UI 同款 ProjectTaskService.saveTask, 建成在项目工作台任务看板可见。 */
@Component
@RequiredArgsConstructor
public class CreateProjectTaskTool implements AiTool {

    private final ProjectTaskService projectTaskService;

    @Override public String name() { return "create_project_task"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "在指定项目下新建任务/待办(写操作, 需人工审批)。把规划拆成可跟踪任务。建成在项目工作台任务看板可见。参数 projectId、title 必填; priority(HIGH/MEDIUM/LOW)、assignee、description 可选。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"},\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"任务标题\"},\"priority\":{\"type\":\"string\",\"required\":false,\"desc\":\"HIGH/MEDIUM/LOW\"},\"assignee\":{\"type\":\"string\",\"required\":false},\"description\":{\"type\":\"string\",\"required\":false}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            String title = AgentArgs.str(args, "title");
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            if (title == null) return AiToolResult.fail("bad_arguments", "title 不能为空");
            DnProjectTask t = new DnProjectTask();
            t.setTitle(title);
            t.setPriority(AgentArgs.str(args, "priority"));
            t.setAssignee(AgentArgs.str(args, "assignee"));
            t.setDescription(AgentArgs.str(args, "description"));
            if (ctx != null && ctx.getUserName() != null) t.setCreatedBy(ctx.getUserName());
            DnProjectTask saved = projectTaskService.saveTask(projectId, t);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            out.put("_deeplink", AgentArgs.link("project", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
