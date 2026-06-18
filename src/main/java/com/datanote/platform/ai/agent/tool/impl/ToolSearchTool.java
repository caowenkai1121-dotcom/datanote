package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.engine.ToolRetrievalService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolRegistry;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * tool_search：按意图/关键词检索可用工具(渐进工具披露的桥接)。
 * 工具数增多(接 MCP 等)后, 系统提示只放核心工具 + 本工具, 其余按需经 tool_search 发现再调用,
 * 避免全量 schema 挤占上下文。当前工具数未超阈值时清单仍全量, 本工具作为发现辅助。
 */
@Component
@RequiredArgsConstructor
public class ToolSearchTool implements AiTool {

    // @Lazy 打破与 AiToolRegistry 的循环(注册表收集所有 AiTool 含本工具)
    @Lazy
    @Autowired
    private AiToolRegistry toolRegistry;

    private final ToolRetrievalService toolRetrieval;   // Tool RAG 语义打分(降级回退关键字)
    private final ObjectMapper objectMapper;            // 解析参数 schema 随结果返回

    @Override public String name() { return "tool_search"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "按意图语义检索可用工具(当上文未列出你想要的工具时, 用它发现)。参数 query(自然语言描述你想做什么), limit(默认8)。"
                + "返回匹配工具的 name/desc/params(含参数schema), 发现后即可直接按 params 正确调用。优先用自然语言意图描述而非单关键词。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"query\":{\"type\":\"string\",\"required\":true,\"desc\":\"想做什么/关键词\"},"
                + "\"limit\":{\"type\":\"number\",\"required\":false,\"desc\":\"返回数, 默认8\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String query = AgentArgs.str(args, "query");
        if (query == null) return AiToolResult.fail("bad_arguments", "query 不能为空");
        int limit = AgentArgs.intVal(args, "limit", 8);
        if (limit < 1) limit = 8;
        if (limit > 30) limit = 30;
        String[] terms = query.toLowerCase().split("[\\s,，、/]+");

        // Tool RAG: 先取语义相似度(EmbeddingService 不可用则 null, 回退纯关键字)
        Map<String, Double> sem = toolRetrieval.semanticScores(query, toolRegistry.all());
        boolean semantic = sem != null;

        List<Map<String, Object>> scored = new ArrayList<>();
        for (AiTool t : toolRegistry.all()) {
            if (t == null || "tool_search".equals(t.name())) continue;
            String hay = (nz(t.name()) + " " + nz(t.group()) + " " + nz(t.description())).toLowerCase();
            int kw = 0;
            for (String term : terms) {
                if (term.isEmpty()) continue;
                if (hay.contains(term)) kw += 2;
                if (t.name().toLowerCase().contains(term)) kw += 3; // 命中工具名加权
            }
            double semScore = (semantic && sem.containsKey(t.name())) ? sem.get(t.name()) : 0d;
            // 候选: 关键字命中 或 语义相似度达阈值(纯关键字模式仅看 kw)
            if (kw == 0 && !(semantic && semScore >= 0.25)) continue;
            // 综合分: 语义为主(×100)叠加关键字加权(精确名命中等); 纯关键字模式仅用 kw
            double combined = semantic ? semScore * 100 + kw : kw;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("group", t.group());
            m.put("desc", t.description());
            m.put("readOnly", t.readOnly());
            m.put("params", parseParams(t.paramsSchemaJson())); // 带参数 schema, 发现后即可正确调用
            m.put("_score", combined);
            scored.add(m);
        }
        scored.sort((a, b) -> Double.compare((Double) b.get("_score"), (Double) a.get("_score")));
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            Map<String, Object> m = scored.get(i);
            m.remove("_score");
            top.add(m);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("query", query);
        d.put("engine", semantic ? "semantic" : "keyword");
        d.put("count", top.size());
        d.put("tools", top);
        if (top.isEmpty()) d.put("note", "无匹配工具, 换种意图描述或查看已列出的工具");
        return AiToolResult.ok(d);
    }

    private Object parseParams(String schema) {
        if (schema == null || schema.isEmpty()) return new LinkedHashMap<>();
        try { return objectMapper.readTree(schema); } catch (Exception e) { return schema; }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
