package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.engine.DocIngestService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * doc_search: 检索用户上传的 PDF/Word 文档知识库(向量召回, owner 隔离)。
 * 回答涉及用户上传文档内容时用此工具找正文片段, 再据片段作答(注明来源文件名)。
 * 权限: requiredPerm=null(仅需登录), 但服务层按发起人 owner 隔离, 只能检索本人可见文档。
 */
@Component
@RequiredArgsConstructor
public class DocSearchTool implements AiTool {

    private final DocIngestService docIngestService;

    @Override public String name() { return "doc_search"; }
    @Override public String group() { return "metadata"; }
    @Override public String description() {
        return "检索我上传的文档知识库(PDF/Word/txt/md 切块入向量库)。当问题涉及上传文档的内容时用自然语言检索相关正文片段, 据片段作答并注明来源文件名。参数 query 必填(检索词), limit 可选(默认5)。仅检索本人上传的文档; 向量库未就绪时返回空。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"query\":{\"type\":\"string\",\"required\":true,\"desc\":\"检索词(自然语言)\"},\"limit\":{\"type\":\"number\",\"required\":false,\"desc\":\"返回片段数,默认5\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String query = AgentArgs.str(args, "query");
        if (query == null) return AiToolResult.fail("bad_arguments", "query 不能为空");
        int limit = AgentArgs.intVal(args, "limit", 5);
        String caller = ctx == null ? null : ctx.getUserName();
        return AiToolResult.ok(docIngestService.searchDocs(query, caller, limit));
    }
}
