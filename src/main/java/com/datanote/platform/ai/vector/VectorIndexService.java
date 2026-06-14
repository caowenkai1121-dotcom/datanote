package com.datanote.platform.ai.vector;

import com.datanote.domain.governance.mapper.DnGlossaryTermMapper;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.mapper.DnDataElementMapper;
import com.datanote.domain.governance.mapper.DnWordRootMapper;
import com.datanote.domain.governance.model.DnGlossaryTerm;
import com.datanote.domain.governance.model.DnMetric;
import com.datanote.domain.governance.model.DnDataElement;
import com.datanote.domain.governance.model.DnWordRoot;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.domain.metadata.model.DnColumnMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 元数据/知识 → 向量库 索引。覆盖 表(table)/业务术语(glossary)/指标(metric)/数据标准(dataelement)/命名词根(wordroot); 列级顺延。
 * 降级: 向量库或 embedding 不可用 → fullReindex 直接 return 0, 采集主流程零感知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService {

    private final VectorStoreClient vector;
    private final EmbeddingService embedding;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnGlossaryTermMapper glossaryTermMapper;
    private final DnMetricMapper metricMapper;
    private final DnDataElementMapper dataElementMapper;
    private final DnWordRootMapper wordRootMapper;

    private static final int BATCH = 64;

    // 列级重建状态(异步作业, 供 /sync-columns/status 轮询)
    private final java.util.concurrent.atomic.AtomicBoolean columnIndexing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile int lastColumnPoints = -1;

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
        try { n += indexDataElement(); } catch (Exception e) { log.warn("[vector] 数据元索引失败: {}", e.getMessage()); }
        try { n += indexWordRoot(); } catch (Exception e) { log.warn("[vector] 词根索引失败: {}", e.getMessage()); }
        return n;
    }

    /**
     * 列级重建(独立入口): 列量大(数千~万), 不并进常规 fullReindex 以免拖慢; 由 /store/sync-columns 单独触发。
     * 返回写入点数(不可用返 0)。
     */
    public int reindexColumns() {
        if (!vector.available() || !embedding.isAvailable()) {
            log.info("[vector] reindexColumns 跳过(vector={}, embedding={})", vector.available(), embedding.isAvailable());
            return 0;
        }
        if (!vector.ensureCollection(embedding.dim())) return 0;
        try { return indexColumns(); } catch (Exception e) { log.warn("[vector] 列索引失败: {}", e.getMessage()); return 0; }
    }

    /** 异步触发列级重建(返回即走, 后台串行跑, 状态见 {@link #columnIndexStatus()})。已在跑则忽略。 */
    @Async("aiIndexExecutor")
    public void reindexColumnsAsync() {
        // 原子抢占: 并发触发时仅首个进入, 杜绝 check-then-act 的 TOCTOU 重复重建
        if (!columnIndexing.compareAndSet(false, true)) return;
        try {
            lastColumnPoints = reindexColumns();
        } catch (Exception e) {
            log.warn("[vector] 列级异步重建异常: {}", e.getMessage());
        } finally {
            columnIndexing.set(false);
        }
    }

    /** 列级重建状态(供前端/轮询)。 */
    public Map<String, Object> columnIndexStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexing", columnIndexing.get());
        m.put("lastColumnPoints", lastColumnPoints);
        return m;
    }

    private int indexColumns() {
        List<DnColumnMeta> all = columnMetaMapper.selectList(null);
        if (all == null || all.isEmpty()) return 0;
        // 表id → [db, name] 映射(列文本需带所属表上下文, 一次性载入避免逐列回查)
        Map<Long, String[]> tmap = new HashMap<>();
        List<DnTableMeta> tabs = tableMetaMapper.selectList(null);
        if (tabs != null) for (DnTableMeta t : tabs) {
            if (t != null && t.getId() != null) tmap.put(t.getId(), new String[]{nz(t.getDatabaseName()), nz(t.getTableName())});
        }
        int total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            List<DnColumnMeta> chunk = all.subList(i, Math.min(i + BATCH, all.size()));
            List<String> texts = new ArrayList<>();
            List<DnColumnMeta> valid = new ArrayList<>();
            for (DnColumnMeta c : chunk) {
                if (c == null || c.getId() == null || c.getColumnName() == null) continue;
                texts.add(columnText(c, tmap.get(c.getTableMetaId())));
                valid.add(c);
            }
            if (texts.isEmpty()) continue;
            List<float[]> vecs = embedding.embedBatch(texts);
            if (vecs == null || vecs.size() != valid.size()) { log.warn("[vector] 列批次嵌入数不匹配, 跳过"); continue; }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int k = 0; k < valid.size(); k++) {
                DnColumnMeta c = valid.get(k);
                String[] dt = tmap.get(c.getTableMetaId());
                String title = (c.getBusinessName() != null && !c.getBusinessName().trim().isEmpty())
                        ? c.getBusinessName() : nz(c.getBusinessDesc());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "column");
                payload.put("db", dt == null ? "" : dt[0]);
                payload.put("table", dt == null ? "" : dt[1]);
                payload.put("name", c.getColumnName());
                payload.put("title", title);
                payload.put("text", texts.get(k));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pointId("column", c.getId()));
                p.put("vector", vecs.get(k));
                p.put("payload", payload);
                points.add(p);
            }
            if (vector.upsert(points)) total += points.size();
        }
        log.info("[vector] 列元数据索引完成: {} 点", total);
        return total;
    }

    private static String columnText(DnColumnMeta c, String[] dt) {
        StringBuilder sb = new StringBuilder("字段 ").append(c.getColumnName());
        if (c.getBusinessName() != null && !c.getBusinessName().trim().isEmpty()) sb.append("(").append(c.getBusinessName()).append(")");
        if (dt != null) sb.append(" 属于表 ").append(dt[0]).append('.').append(dt[1]);
        if (c.getDataType() != null && !c.getDataType().trim().isEmpty()) sb.append(" 类型:").append(c.getDataType());
        if (c.getBusinessDesc() != null && !c.getBusinessDesc().trim().isEmpty()) sb.append(" 说明:").append(c.getBusinessDesc());
        return sb.toString();
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

    private int indexDataElement() {
        List<DnDataElement> all = dataElementMapper.selectList(null);
        if (all == null || all.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            List<DnDataElement> chunk = all.subList(i, Math.min(i + BATCH, all.size()));
            List<String> texts = new ArrayList<>();
            List<DnDataElement> valid = new ArrayList<>();
            for (DnDataElement d : chunk) {
                if (d == null || d.getId() == null || d.getNameCn() == null) continue;
                texts.add(dataElementText(d));
                valid.add(d);
            }
            if (texts.isEmpty()) continue;
            List<float[]> vecs = embedding.embedBatch(texts);
            if (vecs == null || vecs.size() != valid.size()) { log.warn("[vector] 数据元批次嵌入数不匹配, 跳过"); continue; }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int k = 0; k < valid.size(); k++) {
                DnDataElement d = valid.get(k);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "dataelement");
                payload.put("name", d.getNameCn());
                payload.put("code", nz(d.getElementCode()));
                payload.put("title", nz(d.getDescription()));
                payload.put("text", texts.get(k));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pointId("dataelement", d.getId()));
                p.put("vector", vecs.get(k));
                p.put("payload", payload);
                points.add(p);
            }
            if (vector.upsert(points)) total += points.size();
        }
        log.info("[vector] 数据元(数据标准)索引完成: {} 点", total);
        return total;
    }

    private int indexWordRoot() {
        List<DnWordRoot> all = wordRootMapper.selectList(null);
        if (all == null || all.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            List<DnWordRoot> chunk = all.subList(i, Math.min(i + BATCH, all.size()));
            List<String> texts = new ArrayList<>();
            List<DnWordRoot> valid = new ArrayList<>();
            for (DnWordRoot w : chunk) {
                if (w == null || w.getId() == null || w.getWordCn() == null) continue;
                texts.add(wordRootText(w));
                valid.add(w);
            }
            if (texts.isEmpty()) continue;
            List<float[]> vecs = embedding.embedBatch(texts);
            if (vecs == null || vecs.size() != valid.size()) { log.warn("[vector] 词根批次嵌入数不匹配, 跳过"); continue; }
            List<Map<String, Object>> points = new ArrayList<>();
            for (int k = 0; k < valid.size(); k++) {
                DnWordRoot w = valid.get(k);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("kind", "wordroot");
                payload.put("name", w.getWordCn());
                payload.put("code", nz(w.getWordEn()));
                payload.put("title", nz(w.getAbbr()));
                payload.put("text", texts.get(k));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", pointId("wordroot", w.getId()));
                p.put("vector", vecs.get(k));
                p.put("payload", payload);
                points.add(p);
            }
            if (vector.upsert(points)) total += points.size();
        }
        log.info("[vector] 命名词根索引完成: {} 点", total);
        return total;
    }

    private static String dataElementText(DnDataElement d) {
        StringBuilder sb = new StringBuilder("数据标准 ").append(d.getNameCn());
        if (d.getElementCode() != null && !d.getElementCode().trim().isEmpty()) sb.append("(").append(d.getElementCode()).append(")");
        if (d.getDataType() != null && !d.getDataType().trim().isEmpty()) sb.append(" 类型:").append(d.getDataType());
        if (d.getValueDomain() != null && !d.getValueDomain().trim().isEmpty()) sb.append(" 值域:").append(d.getValueDomain());
        if (d.getDescription() != null && !d.getDescription().trim().isEmpty()) sb.append(" 说明:").append(d.getDescription());
        return sb.toString();
    }

    private static String wordRootText(DnWordRoot w) {
        StringBuilder sb = new StringBuilder("命名词根 ").append(w.getWordCn());
        if (w.getWordEn() != null && !w.getWordEn().trim().isEmpty()) sb.append("=").append(w.getWordEn());
        if (w.getAbbr() != null && !w.getAbbr().trim().isEmpty()) sb.append("(缩写:").append(w.getAbbr()).append(")");
        if (w.getCategory() != null && !w.getCategory().trim().isEmpty()) sb.append(" 分类:").append(w.getCategory());
        return sb.toString();
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
