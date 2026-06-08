package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.datanote.platform.ai.vector.SemanticSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** semantic_search：语义检索资产(向量库+embedding,降级关键字)。委托 SemanticSearchService(与 REST 端点/前端共用)。永返 ok。 */
@Component
@RequiredArgsConstructor
public class SemanticSearchTool implements AiTool {

    private final SemanticSearchService semanticSearchService;

    @Override public String name() { return "semantic_search"; }
    @Override public String group() { return "metadata"; }
    @Override public String description() {
        return "按语义检索数据资产(表/列/术语/指标)。不知道确切表名时用自然语言找,如'用户支付相关的表'可召回付款/交易/订单等同义表。参数 query 必填(检索词), kind 可选(table/column/glossary/metric 过滤), limit 可选(默认10)。向量库未配置时自动降级关键字检索。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"query\":{\"type\":\"string\",\"required\":true,\"desc\":\"检索词(自然语言)\"},\"kind\":{\"type\":\"string\",\"required\":false,\"desc\":\"table/column/glossary/metric\"},\"limit\":{\"type\":\"number\",\"required\":false,\"desc\":\"返回数,默认10\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String query = AgentArgs.str(args, "query");
        if (query == null) return AiToolResult.fail("bad_arguments", "query 不能为空");
        String kind = AgentArgs.str(args, "kind");
        int limit = AgentArgs.intVal(args, "limit", 10);
        return AiToolResult.ok(semanticSearchService.search(query, kind, limit));
    }
}
