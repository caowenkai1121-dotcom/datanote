package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.project.ProjectWikiService;
import com.datanote.domain.project.model.DnProjectWikiPage;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(LOW): 创建/更新项目 Wiki 页面。createdBy/updatedBy 由服务端通过 ProjectService.currentUser() 写入。 */
@Component
@RequiredArgsConstructor
public class SaveWikiPageTool implements AiTool {

    private final ProjectWikiService projectWikiService;

    @Override public String name() { return "save_wiki_page"; }
    @Override public String group() { return "project"; }
    @Override public String description() {
        return "创建/更新项目 Wiki 页(更新传 id; parentId 组织子页树; updatedBy 服务端自动写)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"projectId\":{\"type\":\"number\",\"required\":true,\"desc\":\"项目ID\"}," +
               "\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"页面标题\"}," +
               "\"id\":{\"type\":\"number\",\"required\":false,\"desc\":\"页面ID(更新时传)\"}," +
               "\"content\":{\"type\":\"string\",\"required\":false,\"desc\":\"页面正文(Markdown)\"}," +
               "\"parentId\":{\"type\":\"number\",\"required\":false,\"desc\":\"父页面ID(不传则为根页面)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "project:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long projectId = AgentArgs.longOrCtx(args, "projectId", ctx);
            if (projectId == null) return AiToolResult.fail("bad_arguments", "projectId 不能为空");
            String title = AgentArgs.str(args, "title");
            if (title == null) return AiToolResult.fail("bad_arguments", "title 不能为空");
            DnProjectWikiPage p = new DnProjectWikiPage();
            p.setId(AgentArgs.longVal(args, "id"));
            p.setTitle(title);
            p.setContent(AgentArgs.str(args, "content"));
            p.setParentId(AgentArgs.longVal(args, "parentId"));
            DnProjectWikiPage saved = projectWikiService.savePage(projectId, p);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("page", saved);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
