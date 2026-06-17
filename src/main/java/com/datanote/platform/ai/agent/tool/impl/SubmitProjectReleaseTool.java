package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectReleaseService;
import com.datanote.domain.project.model.DnProjectRelease;
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
 * 写工具(HIGH): 提交项目发布，进入审批流。
 * 服务层: 归档项目拒写; assetJson 清单须全为本项目绑定资产; submittedBy 服务端自动写。
 */
@Component
@RequiredArgsConstructor
public class SubmitProjectReleaseTool implements AiTool {

    private final ProjectReleaseService projectReleaseService;

    @Override public String name() { return "submit_project_release"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "提交项目发布(进入审批流; assetJson 每项须为本项目已绑定资产; 归档项目拒提交)。高风险写操作, 每次需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"}," +
               "\"title\":{\"type\":\"string\",\"required\":false,\"desc\":\"发布标题\"}," +
               "\"content\":{\"type\":\"string\",\"required\":false,\"desc\":\"发布说明\"}," +
               "\"targetEnv\":{\"type\":\"string\",\"required\":false,\"desc\":\"目标环境(默认 PROD)\"}," +
               "\"assetJson\":{\"type\":\"string\",\"required\":false,\"desc\":\"资产清单 JSON 数组, 每项 {assetType,assetId,assetName}\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }
    @Override public String requiredPerm() { return "project:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            String title = AgentArgs.str(args, "title");
            String content = AgentArgs.str(args, "content");
            String targetEnv = AgentArgs.str(args, "targetEnv");
            String assetJson = AgentArgs.str(args, "assetJson");
            DnProjectRelease release = projectReleaseService.submit(projectId, title, content, targetEnv, assetJson);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("release", release);
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
