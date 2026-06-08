package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectOverviewService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：项目总览(资产/成员/发布/活动/健康)。封装 ProjectOverviewService.overview。带 _deeplink 回项目管理。 */
@Component
@RequiredArgsConstructor
public class ProjectOverviewTool implements AiTool {

    private final ProjectOverviewService projectOverviewService;

    @Override public String name() { return "project_overview"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "查项目总览(资产数/成员/发布统计/活动流/绑定同步任务运行)。盘点项目进度与健康。参数 projectId 必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("overview", projectOverviewService.overview(projectId));
            out.put("_deeplink", AgentArgs.link("project", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
