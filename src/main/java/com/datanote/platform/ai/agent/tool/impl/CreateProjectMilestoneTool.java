package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectTaskService;
import com.datanote.domain.project.model.DnProjectMilestone;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写工具(LOW): 创建项目里程碑。
 * 注: DnProjectMilestone 无 dueDate 字段, 用 endDate 表达截止日; startDate 可选。
 */
@Component
@RequiredArgsConstructor
public class CreateProjectMilestoneTool implements AiTool {

    private final ProjectTaskService projectTaskService;

    @Override public String name() { return "create_project_milestone"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "创建项目里程碑(name 必填; 可选 dueDate/description)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"}," +
               "\"name\":{\"type\":\"string\",\"required\":true,\"desc\":\"里程碑名称\"}," +
               "\"dueDate\":{\"type\":\"string\",\"required\":false,\"desc\":\"截止日期 yyyy-MM-dd\"}," +
               "\"description\":{\"type\":\"string\",\"required\":false,\"desc\":\"描述\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "project:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            String name = AgentArgs.str(args, "name");
            if (name == null) return AiToolResult.fail("bad_arguments", "name 不能为空");
            DnProjectMilestone m = new DnProjectMilestone();
            m.setName(name);
            m.setDescription(AgentArgs.str(args, "description"));
            String dueDateStr = AgentArgs.str(args, "dueDate");
            if (dueDateStr != null) {
                try {
                    m.setEndDate(LocalDate.parse(dueDateStr));
                } catch (DateTimeParseException e) {
                    return AiToolResult.fail("bad_arguments", "dueDate 须为 yyyy-MM-dd: " + dueDateStr);
                }
            }
            DnProjectMilestone saved = projectTaskService.saveMilestone(projectId, m);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("milestone", saved);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
