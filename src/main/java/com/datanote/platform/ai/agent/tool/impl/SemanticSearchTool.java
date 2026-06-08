package com.datanote.platform.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.datanote.platform.ai.vector.EmbeddingService;
import com.datanote.platform.ai.vector.VectorStoreClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** semantic_search：语义检索资产(向量库+embedding)。不可用时降级关键字 LIKE。永返 ok(带 engine 标记)。 */
@Component
@RequiredArgsConstructor
public class SemanticSearchTool implements AiTool {

    private final VectorStoreClient vector;
    private final EmbeddingService embedding;
    private final DnTableMetaMapper tableMetaMapper;

    @Override public String name() { return "semantic_search"; }
    @Override public String group() { return "metadata"; }
    @Override public String description() {
        return "按语义检索数据资产(表/列/术语/指标)。不知道确切表名时用自然语言找,如'用户支付相关的表'可召回付款/交易/订单等同义表。参数 query 必填(检索词), kind 可选(table/column/glossary/metric 过滤), limit 可选(默认10)。向量库未配置时自动降级为关键字检索。";
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
        int limit = clamp(AgentArgs.intVal(args, "limit", 10), 1, 50);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);

        // 向量优先
        if (vector.available() && embedding.isAvailable()) {
            try {
                float[] v = embedding.embed(query);
                if (v != null) {
                    List<Map<String, Object>> hits = vector.search(v, kind, limit);
                    if (hits != null) {
                        out.put("engine", "vector");
                        out.put("count", hits.size());
                        out.put("results", hits);
                        return AiToolResult.ok(out);
                    }
                }
            } catch (Exception ignore) {
            }
        }
        // 关键字兜底(表名/注释 LIKE)
        out.put("engine", "keyword_fallback");
        out.put("note", "向量库/嵌入未配置, 已降级为关键字检索");
        out.put("results", keywordTables(query, limit));
        return AiToolResult.ok(out);
    }

    private List<Map<String, Object>> keywordTables(String q, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            QueryWrapper<DnTableMeta> qw = new QueryWrapper<DnTableMeta>()
                    .like("table_name", q).or().like("table_comment", q)
                    .last("LIMIT " + limit);
            List<DnTableMeta> rows = tableMetaMapper.selectList(qw);
            if (rows != null) {
                for (DnTableMeta t : rows) {
                    if (t == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", "table");
                    m.put("db", t.getDatabaseName());
                    m.put("name", t.getTableName());
                    m.put("title", t.getTableComment());
                    out.add(m);
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
