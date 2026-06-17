package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectService;
import com.datanote.domain.project.model.DnProject;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 新建项目。映射 UI 同款 ProjectService.save(自动生成 projectCode+owner入成员), 建成在项目管理列表可见。 */
@Component
@RequiredArgsConstructor
public class CreateProjectTool implements AiTool {

    private final ProjectService projectService;

    @Override public String name() { return "create_project"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "新建项目(写操作, 需人工审批)。建成后在项目管理列表可见。参数 projectName 必填; projectType/env/description/sensitivity 可选。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectName\":{\"type\":\"string\",\"required\":true,\"desc\":\"项目名\"},\"projectType\":{\"type\":\"string\",\"required\":false,\"desc\":\"项目类型(DATASYNC/GOVERNANCE/...)\"},\"env\":{\"type\":\"string\",\"required\":false,\"desc\":\"环境(DEV/TEST/PROD)\"},\"description\":{\"type\":\"string\",\"required\":false},\"sensitivity\":{\"type\":\"string\",\"required\":false}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "project:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String name = AgentArgs.str(args, "projectName");
            if (name == null) return AiToolResult.fail("bad_arguments", "projectName 不能为空");
            DnProject p = new DnProject();
            p.setProjectName(name);
            p.setProjectType(AgentArgs.str(args, "projectType"));
            p.setEnv(AgentArgs.str(args, "env"));
            p.setDescription(AgentArgs.str(args, "description"));
            p.setSensitivity(AgentArgs.str(args, "sensitivity"));
            if (ctx != null && ctx.getUserName() != null) { p.setOwner(ctx.getUserName()); p.setCreatedBy(ctx.getUserName()); }
            DnProject saved = projectService.save(p);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            out.put("_deeplink", AgentArgs.link("project", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
