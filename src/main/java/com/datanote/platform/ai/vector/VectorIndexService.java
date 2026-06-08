package com.datanote.platform.ai.vector;

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
        try {
            return indexTables();
        } catch (Exception e) {
            log.warn("[vector] fullReindex 失败: {}", e.getMessage());
            return 0;
        }
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
