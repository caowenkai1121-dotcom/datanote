package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.governance.AssetDetailService;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmXrefMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmXref;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * MDM 源表导入·整合管线(MDM 本质): 从源库表【消费记录】→ 按映射组装属性 → 按 (源系统,源ID) 与业务主键【匹配】
 * → 命中则【合并】(源值覆盖映射字段, version+1) 否则【新建】黄金记录 → 维护 XREF(源ID↔黄金记录ID)。
 * 让黄金记录从"手工录入"转为"多源消费合并", 把 dedup/存活/xref 真正串起来。幂等: 重复导入更新同一黄金记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdmIngestService {

    private final AssetDetailService assetDetailService;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmXrefMapper xrefMapper;
    private final ObjectMapper objectMapper;

    /** 读源表前若干列名(供前端配置字段映射)。 */
    public List<String> sourceColumns(String db, String table) throws Exception {
        Map<String, Object> r = assetDetailService.readRows(db, table, 1);
        Object cols = r.get("columns");
        return (cols instanceof List) ? castStrList((List<?>) cols) : new ArrayList<>();
    }

    /** 从源表导入黄金记录。mapping: 源列名→属性编码。返回 {total,created,updated,skipped,sourceSystem,errors}。 */
    public Map<String, Object> importFromTable(Long entityId, String db, String table, Map<String, String> mapping,
                                               String sourceSystem, String sourceIdColumn, int limit,
                                               boolean activate, String operator) throws Exception {
        if (entityId == null) throw new BusinessException("请选择目标实体");
        DnMdmEntity entity = entityMapper.selectById(entityId);
        if (entity == null) throw new ResourceNotFoundException("目标实体");
        if (db == null || table == null) throw new BusinessException("请选择源库与源表");
        if (mapping == null || mapping.isEmpty()) throw new BusinessException("请配置字段映射(源列→属性)");
        String srcSys = (sourceSystem == null || sourceSystem.trim().isEmpty()) ? db : sourceSystem.trim();

        List<DnMdmAttribute> attrs = attributeMapper.selectList(new QueryWrapper<DnMdmAttribute>().eq("entity_id", entityId));

        Map<String, Object> preview = assetDetailService.readRows(db, table, limit);
        List<String> cols = castStrList((List<?>) preview.getOrDefault("columns", new ArrayList<>()));
        List<List<Object>> rows = castRows(preview.get("rows"));
        Map<String, Integer> colIdx = new HashMap<>();
        for (int i = 0; i < cols.size(); i++) colIdx.put(cols.get(i), i);

        int total = 0, created = 0, updated = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // —— pass1: 组装 + 批内按源ID去重(同一源ID源行后值覆盖, 避免同客户多订单行把版本翻倍) ——
        LinkedHashMap<String, Map<String, Object>> bySourceId = new LinkedHashMap<>();
        LinkedHashMap<String, String> bizKeyOf = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            total++;
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, String> m : mapping.entrySet()) {
                Integer idx = colIdx.get(m.getKey());
                if (idx == null || idx >= row.size()) continue;
                Object v = row.get(idx);
                if (v != null && !String.valueOf(v).trim().isEmpty()) values.put(m.getValue(), v);
            }
            String missing = firstMissingRequired(attrs, values);
            if (missing != null) {
                skipped++;
                if (errors.size() < 10) errors.add("第" + total + "行: 必填属性[" + missing + "]缺失, 已跳过");
                continue;
            }
            String bizKey = computeBizKey(attrs, values);
            String sourceId = null;
            if (sourceIdColumn != null && colIdx.containsKey(sourceIdColumn)) {
                Object sv = row.get(colIdx.get(sourceIdColumn));
                if (sv != null) sourceId = String.valueOf(sv).trim();
            }
            if (sourceId == null || sourceId.isEmpty()) sourceId = bizKey;
            Map<String, Object> exist = bySourceId.get(sourceId);
            if (exist == null) { bySourceId.put(sourceId, values); bizKeyOf.put(sourceId, bizKey); }
            else exist.putAll(values); // 批内同源ID合并
        }

        // —— 预载: 该源系统已有 XREF(sourceId→goldenId) + 该实体未停用黄金记录(id→记录, bizKey→记录), 内存匹配免逐行查库 ——
        Map<String, DnMdmXref> xrefBySrcId = new HashMap<>();
        for (DnMdmXref x : xrefMapper.selectList(new QueryWrapper<DnMdmXref>().eq("source_system", srcSys))) {
            if (x.getSourceId() != null) xrefBySrcId.put(x.getSourceId(), x);
        }
        Map<Long, DnMdmGoldenRecord> goldenById = new HashMap<>();
        Map<String, DnMdmGoldenRecord> goldenByBizKey = new HashMap<>();
        for (DnMdmGoldenRecord g : goldenMapper.selectList(new QueryWrapper<DnMdmGoldenRecord>().eq("entity_id", entityId).ne("status", "inactive"))) {
            goldenById.put(g.getId(), g);
            if (g.getBizKey() != null) goldenByBizKey.put(g.getBizKey(), g);
        }

        // —— pass2: 逐个去重后的源ID 做匹配→合并/新建(只写变化, 不再逐行查库) ——
        for (Map.Entry<String, Map<String, Object>> e : bySourceId.entrySet()) {
            String sourceId = e.getKey();
            Map<String, Object> values = e.getValue();
            String bizKey = bizKeyOf.get(sourceId);
            try {
                DnMdmXref xr = xrefBySrcId.get(sourceId);
                DnMdmGoldenRecord g = (xr != null && xr.getGoldenRecordId() != null) ? goldenById.get(xr.getGoldenRecordId()) : null;
                if (g == null) g = goldenByBizKey.get(bizKey);
                if (g != null) {
                    Map<String, Object> merged = parse(g.getDataJson());
                    merged.putAll(values); // 源为权威, 覆盖映射字段
                    g.setDataJson(objectMapper.writeValueAsString(merged));
                    g.setVersion((g.getVersion() == null ? 1 : g.getVersion()) + 1);
                    g.setUpdatedAt(now);
                    if (g.getSourceSystem() == null || g.getSourceSystem().isEmpty()) g.setSourceSystem(srcSys);
                    goldenMapper.updateById(g);
                    updated++;
                    if (xr == null) { xrefBySrcId.put(sourceId, insertXref(g, entityId, srcSys, sourceId, operator, now)); }
                } else {
                    g = new DnMdmGoldenRecord();
                    g.setEntityId(entityId);
                    g.setBizKey(bizKey);
                    g.setDataJson(objectMapper.writeValueAsString(values));
                    g.setStatus(activate ? "active" : "draft");
                    g.setVersion(1);
                    g.setSourceSystem(srcSys);
                    g.setCreatedBy(operator);
                    g.setCreatedAt(now);
                    g.setUpdatedAt(now);
                    goldenMapper.insert(g);
                    created++;
                    goldenById.put(g.getId(), g);
                    if (bizKey != null) goldenByBizKey.put(bizKey, g);
                    xrefBySrcId.put(sourceId, insertXref(g, entityId, srcSys, sourceId, operator, now));
                }
            } catch (Exception ex) {
                skipped++;
                if (errors.size() < 10) errors.add("源ID " + sourceId + ": " + ex.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("created", created);
        out.put("updated", updated);
        out.put("skipped", skipped);
        out.put("sourceSystem", srcSys);
        out.put("errors", errors);
        log.info("[mdm-ingest] entity={} {}.{} total={} created={} updated={} skipped={}", entityId, db, table, total, created, updated, skipped);
        return out;
    }

    private String firstMissingRequired(List<DnMdmAttribute> attrs, Map<String, Object> values) {
        for (DnMdmAttribute a : attrs) {
            if (a.getRequired() != null && a.getRequired() == 1) {
                Object v = values.get(a.getAttrCode());
                if (v == null || String.valueOf(v).trim().isEmpty()) return a.getAttrName() != null ? a.getAttrName() : a.getAttrCode();
            }
        }
        return null;
    }

    private String computeBizKey(List<DnMdmAttribute> attrs, Map<String, Object> values) {
        for (DnMdmAttribute a : attrs) {
            boolean key = (a.getIsKey() != null && a.getIsKey() == 1) || (a.getIsUnique() != null && a.getIsUnique() == 1);
            Object v = values.get(a.getAttrCode());
            if (key && v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v);
        }
        for (DnMdmAttribute a : attrs) {
            Object v = values.get(a.getAttrCode());
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v);
        }
        return "记录-" + System.currentTimeMillis();
    }

    /** 新建一条 XREF(源ID↔黄金记录), 返回之(供内存映射缓存)。 */
    private DnMdmXref insertXref(DnMdmGoldenRecord g, Long entityId, String sourceSystem, String sourceId, String operator, LocalDateTime now) {
        DnMdmXref x = new DnMdmXref();
        x.setGoldenRecordId(g.getId());
        x.setEntityId(entityId);
        x.setSourceSystem(sourceSystem);
        x.setSourceId(sourceId);
        x.setIsPrimary(1);
        x.setCreatedBy(operator);
        x.setCreatedAt(now);
        x.setUpdatedAt(now);
        xrefMapper.insert(x);
        return x;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private static List<String> castStrList(List<?> in) {
        List<String> out = new ArrayList<>();
        if (in != null) for (Object o : in) out.add(o == null ? "" : String.valueOf(o));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> castRows(Object rows) {
        List<List<Object>> out = new ArrayList<>();
        if (rows instanceof List) for (Object r : (List<?>) rows) if (r instanceof List) out.add((List<Object>) r);
        return out;
    }
}
