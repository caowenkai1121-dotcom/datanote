package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnMdmAttributeMapper;
import com.datanote.mapper.DnMdmGoldenRecordMapper;
import com.datanote.model.DnMdmAttribute;
import com.datanote.model.DnMdmGoldenRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 主数据匹配去重服务 —— 基于实体关键/唯一属性检测重复黄金记录（供匹配控制器与数据管家工作台共用）。
 */
@Service
@RequiredArgsConstructor
public class MdmMatchService {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 检测某实体的重复黄金记录，返回统计 + 各重复簇。 */
    public Map<String, Object> detectDuplicates(Long entityId) {
        // 关键/唯一属性作为匹配键
        QueryWrapper<DnMdmAttribute> aqw = new QueryWrapper<>();
        aqw.eq("entity_id", entityId).and(w -> w.eq("is_key", 1).or().eq("is_unique", 1));
        List<DnMdmAttribute> keyAttrs = attributeMapper.selectList(aqw);

        // 仅对生效/草稿记录去重
        QueryWrapper<DnMdmGoldenRecord> gqw = new QueryWrapper<>();
        gqw.eq("entity_id", entityId).ne("status", "inactive");
        List<DnMdmGoldenRecord> records = goldenMapper.selectList(gqw);

        List<Map<String, Object>> clusters = new ArrayList<>();
        Set<String> seenClusterKey = new HashSet<>();
        for (DnMdmAttribute attr : keyAttrs) {
            Map<String, List<DnMdmGoldenRecord>> byVal = new LinkedHashMap<>();
            for (DnMdmGoldenRecord r : records) {
                Object v = parse(r.getDataJson()).get(attr.getAttrCode());
                if (v == null) continue;
                String norm = String.valueOf(v).trim().toLowerCase();
                if (norm.isEmpty()) continue;
                byVal.computeIfAbsent(norm, k -> new ArrayList<>()).add(r);
            }
            for (Map.Entry<String, List<DnMdmGoldenRecord>> e : byVal.entrySet()) {
                if (e.getValue().size() < 2) continue;
                List<Long> ids = new ArrayList<>();
                for (DnMdmGoldenRecord r : e.getValue()) ids.add(r.getId());
                Collections.sort(ids);
                String ck = ids.toString();
                if (seenClusterKey.contains(ck)) continue;
                seenClusterKey.add(ck);
                Map<String, Object> cluster = new HashMap<>();
                cluster.put("matchAttrCode", attr.getAttrCode());
                cluster.put("matchAttrName", attr.getAttrName());
                cluster.put("matchValue", parse(e.getValue().get(0).getDataJson()).get(attr.getAttrCode()));
                cluster.put("size", e.getValue().size());
                cluster.put("records", e.getValue());
                clusters.add(cluster);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("clusterCount", clusters.size());
        int dupRecords = 0;
        for (Map<String, Object> c : clusters) dupRecords += ((Integer) c.get("size"));
        data.put("duplicateRecordCount", dupRecords);
        data.put("scannedCount", records.size());
        data.put("keyAttrCount", keyAttrs.size());
        data.put("clusters", clusters);
        return data;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return new HashMap<>(); }
    }
}
