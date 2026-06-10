package com.datanote.platform.ai.vector;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义检索核心(供 semantic_search 工具 + REST 端点 + 前端共用, DRY)。
 * 向量库+embedding 可用 → 向量召回; 否则降级关键字 LIKE。永不抛异常。
 */
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private final VectorStoreClient vector;
    private final EmbeddingService embedding;
    private final DnTableMetaMapper tableMetaMapper;

    /** 语义检索。返回 {query, engine:vector|keyword_fallback, count, results:[{kind,db,name,title,score}], note?}。 */
    public Map<String, Object> search(String query, String kind, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        int lim = limit < 1 ? 10 : (limit > 50 ? 50 : limit);
        if (query == null || query.trim().isEmpty()) {
            out.put("engine", "none");
            out.put("results", new ArrayList<>());
            return out;
        }
        // 向量优先
        if (vector.available() && embedding.isAvailable()) {
            try {
                float[] v = embedding.embed(query.trim());
                if (v != null) {
                    List<Map<String, Object>> hits = vector.search(v, kind, lim);
                    if (hits != null) {
                        out.put("engine", "vector");
                        out.put("count", hits.size());
                        out.put("results", normalize(hits));
                        return out;
                    }
                }
            } catch (Exception ignore) {
                // 落入关键字兜底
            }
        }
        // 关键字兜底
        out.put("engine", "keyword_fallback");
        out.put("note", "向量库/嵌入未就绪, 已降级关键字检索");
        out.put("results", keywordTables(query.trim(), lim));
        return out;
    }

    /** 把 Qdrant 命中(含 payload)拍平为统一结果项。 */
    private List<Map<String, Object>> normalize(List<Map<String, Object>> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> h : hits) {
            if (h == null) continue;
            Object pl = h.get("payload");
            Map<?, ?> p = (pl instanceof Map) ? (Map<?, ?>) pl : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", p == null ? null : p.get("kind"));
            m.put("db", p == null ? null : p.get("db"));
            m.put("table", p == null ? null : p.get("table")); // 列(column)所属表, 供"字段 X 在表 Y"呈现
            m.put("name", p == null ? null : p.get("name"));
            m.put("title", p == null ? null : p.get("title"));
            m.put("score", h.get("score"));
            out.add(m);
        }
        return out;
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
}
