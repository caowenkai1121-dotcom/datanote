package com.datanote.platform.ai.vector;

import com.datanote.domain.governance.mapper.DnGlossaryTermMapper;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnGlossaryTerm;
import com.datanote.domain.governance.model.DnMetric;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 元数据 → 向量库 索引。本轮覆盖表元数据(kind=table); 列/术语/指标顺延下一轮。
 * 降级: 向量库或 embedding 不可用 → fullReindex 直接 return 0, 采集主流程零感知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService {

    private final VectorStoreClient vector;
    private final EmbeddingService embedding;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnGlossaryTermMapper glossaryTermMapper;
    private final DnMetricMapper metricMapper;

    private static final int BATCH = 64;

    /** 全量重建索引。返回写入向量点数(不可用返 0)。 */
    public int fullReindex() {
        if (!vector.available() || !embedding.isAvailable()) {
            log.info("[vector] fullReindex 跳过(vector.available={}, embedding.available={})",
                    vector.available(), embedding.isAvailable());
            return 0;
        }
        if (!vector.ensureCollection(embedding.dim())) {
            log.warn("[vector] 建集合失败, 跳过重建");
            return 0;
        }
        int n = 0;
        try { n += indexTables(); } catch (Exception e) { log.warn("[vector] 表索引失败: {}", e.getMessage()); }
        try { n += indexGlossary(); } catch (Exception e) { log.warn("[vector] 术语索引失败: {}", e.getMessage()); }
        try { n += indexMetric(); } catch (Exception e) { log.warn("[vector] 指标索引失败: {}", e.getMessage()); }
        return n;
    }

    private int indexTables() {
        List<DnTableMeta> all = tableMetaMapper.selectList(null);
        if (all == null || all.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            List<DnTableMeta> chunk = all.subList(i, Math.min(i + BATCH, all.size()));
            List<String> texts = new ArrayList<>();
            List<DnTableMeta> valid = new ArrayList<>();
            for (DnTableMeta t : chunk) {
                if (t == null || t.getId() == null || t.getTableName() == null) continue;
                texts.add(tableText(t));
                valid.add(t);
            }
            if (texts.isEmpty()) continue;
            List<float[]> vecs = embedding.embedBatch(texts);
            if (vecs == null || vecs.size() != valid.size()) {
                log.warn("[vector] 表批次嵌入数不匹配, 跳过该批");
                continue;
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int k = 0; k < valid.size(); k++) {
                DnTableMeta t = valid.get(k);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "table");
                payload.put("db", nz(t.getDatabaseName()));
                payload.put("name", t.getTableName());
                payload.put("title", nz(t.getTableComment()));
                payload.put("text", texts.get(k));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pointId("table", t.getId()));
                p.put("vector", vecs.get(k));
                p.put("payload", payload);
                points.add(p);
            }
            if (vector.upsert(points)) total += points.size();
        }
        log.info("[vector] 表元数据索引完成: {} 点", total);
        return total;
    }

    private int indexGlossary() {
        List<DnGlossaryTerm> all = glossaryTermMapper.selectList(null);
        if (all == null || all.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            List<DnGlossaryTerm> chunk = all.subList(i, Math.min(i + BATCH, all.size()));
            List<String> texts = new ArrayList<>();
            List<DnGlossaryTerm> valid = new ArrayList<>();
            for (DnGlossaryTerm g : chunk) {
                if (g == null || g.getId() == null || g.getTerm() == null) continue;
                texts.add(glossaryText(g));
                valid.add(g);
            }
            if (texts.isEmpty()) continue;
            List<float[]> vecs = embedding.embedBatch(texts);
            if (vecs == null || vecs.size() != valid.size()) { log.warn("[vector] 术语批次嵌入数不匹配, 跳过"); continue; }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int k = 0; k < valid.size(); k++) {
                DnGlossaryTerm g = valid.get(k);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "glossary");
                payload.put("name", g.getTerm());
                payload.put("title", nz(g.getDefinition()));
                payload.put("text", texts.get(k));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pointId("glossary", g.getId()));
                p.put("vector", vecs.get(k));
                p.put("payload", payload);
                points.add(p);
            }
            if (vector.upsert(points)) total += points.size();
        }
        log.info("[vector] 术语索引完成: {} 点", total);
        return total;
    }

    private int indexMetric() {
        List<DnMetric> all = metricMapper.selectList(null);
        if (all == null || all.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            List<DnMetric> chunk = all.subList(i, Math.min(i + BATCH, all.size()));
            List<String> texts = new ArrayList<>();
            List<DnMetric> valid = new ArrayList<>();
            for (DnMetric m : chunk) {
                if (m == null || m.getId() == null || m.getMetricName() == null) continue;
                texts.add(metricText(m));
                valid.add(m);
            }
            if (texts.isEmpty()) continue;
            List<float[]> vecs = embedding.embedBatch(texts);
            if (vecs == null || vecs.size() != valid.size()) { log.warn("[vector] 指标批次嵌入数不匹配, 跳过"); continue; }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int k = 0; k < valid.size(); k++) {
                DnMetric m = valid.get(k);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "metric");
                payload.put("name", m.getMetricName());
                payload.put("code", nz(m.getMetricCode()));
                payload.put("title", nz(m.getDescription()));
                payload.put("text", texts.get(k));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pointId("metric", m.getId()));
                p.put("vector", vecs.get(k));
                p.put("payload", payload);
                points.add(p);
            }
            if (vector.upsert(points)) total += points.size();
        }
        log.info("[vector] 指标索引完成: {} 点", total);
        return total;
    }

    private static String glossaryText(DnGlossaryTerm g) {
        StringBuilder sb = new StringBuilder("业务术语 ").append(g.getTerm());
        if (g.getAlias() != null && !g.getAlias().trim().isEmpty()) sb.append("(别名:").append(g.getAlias()).append(")");
        if (g.getDefinition() != null && !g.getDefinition().trim().isEmpty()) sb.append(" 定义:").append(g.getDefinition());
        if (g.getCategory() != null && !g.getCategory().trim().isEmpty()) sb.append(" 分类:").append(g.getCategory());
        return sb.toString();
    }

    private static String metricText(DnMetric m) {
        StringBuilder sb = new StringBuilder("指标 ").append(m.getMetricName());
        if (m.getMetricCode() != null && !m.getMetricCode().trim().isEmpty()) sb.append("(").append(m.getMetricCode()).append(")");
        if (m.getDescription() != null && !m.getDescription().trim().isEmpty()) sb.append(" 说明:").append(m.getDescription());
        if (m.getCalcFormula() != null && !m.getCalcFormula().trim().isEmpty()) sb.append(" 口径:").append(m.getCalcFormula());
        if (m.getCategory() != null && !m.getCategory().trim().isEmpty()) sb.append(" 分类:").append(m.getCategory());
        return sb.toString();
    }

    private static String tableText(DnTableMeta t) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据表 ").append(nz(t.getDatabaseName())).append('.').append(t.getTableName());
        if (t.getTableComment() != null && !t.getTableComment().trim().isEmpty()) {
            sb.append(" 说明: ").append(t.getTableComment());
        }
        return sb.toString();
    }

    /** 稳定 point id(更新而非重插): UUID(kind:id)。 */
    private static String pointId(String kind, Object id) {
        return UUID.nameUUIDFromBytes((kind + ":" + id).getBytes()).toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
