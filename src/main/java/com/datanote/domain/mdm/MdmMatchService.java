package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmSurvivorshipRuleMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmSurvivorshipRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据匹配去重服务 —— 基于实体关键/唯一属性检测重复黄金记录（供匹配控制器与数据管家工作台共用）。
 */
@Service
@RequiredArgsConstructor
public class MdmMatchService {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmSurvivorshipRuleMapper survivorshipMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 应用存活性规则：对有规则的属性，从簇内全部记录中按策略(最新/最完整/源优先)挑选最佳值，
     * 组合写入存活记录的 data_json，实现字段级黄金记录生成。返回被改写的字段说明清单。
     */
    public List<String> applySurvivorship(Long entityId, DnMdmGoldenRecord survivor, List<DnMdmGoldenRecord> allRecords) {
        QueryWrapper<DnMdmSurvivorshipRule> rqw = new QueryWrapper<>();
        rqw.eq("entity_id", entityId).orderByAsc("priority");
        List<DnMdmSurvivorshipRule> rules = survivorshipMapper.selectList(rqw);
        if (rules.isEmpty()) return new ArrayList<>();

        Map<String, Object> survivorVals = parse(survivor.getDataJson());
        List<String> applied = new ArrayList<>();
        for (DnMdmSurvivorshipRule rule : rules) {
            String attr = rule.getAttrCode();
            Object best = chooseValue(rule, allRecords, attr);
            if (best != null && !String.valueOf(best).trim().isEmpty()) {
                Object old = survivorVals.get(attr);
                if (old == null || !String.valueOf(old).equals(String.valueOf(best))) {
                    survivorVals.put(attr, best);
                    applied.add((rule.getAttrName() != null ? rule.getAttrName() : attr) + "→" + best + "(" + rule.getStrategy() + ")");
                }
            }
        }
        if (!applied.isEmpty()) {
            try { survivor.setDataJson(objectMapper.writeValueAsString(survivorVals)); } catch (Exception e) { /* 保持原值 */ }
        }
        return applied;
    }

    /** 按单条存活策略，从所有记录中挑选某属性的最佳值。 */
    private Object chooseValue(DnMdmSurvivorshipRule rule, List<DnMdmGoldenRecord> records, String attr) {
        String strategy = rule.getStrategy() == null ? "latest" : rule.getStrategy();
        // 收集有非空值的候选(记录 + 解析后的属性值)
        List<Object[]> cands = new ArrayList<>();   // [record, value]
        for (DnMdmGoldenRecord r : records) {
            Object v = parse(r.getDataJson()).get(attr);
            if (v != null && !String.valueOf(v).trim().isEmpty()) cands.add(new Object[]{r, v});
        }
        if (cands.isEmpty()) return null;
        if ("most_complete".equals(strategy)) {
            Object[] win = cands.get(0);
            for (Object[] c : cands) if (String.valueOf(c[1]).length() > String.valueOf(win[1]).length()) win = c;
            return win[1];
        }
        if ("source_priority".equals(strategy)) {
            String[] order = (rule.getSourcePriority() == null ? "" : rule.getSourcePriority()).split(",");
            for (String sys : order) {
                String s = sys.trim();
                if (s.isEmpty()) continue;
                for (Object[] c : cands) {
                    String src = ((DnMdmGoldenRecord) c[0]).getSourceSystem();
                    if (s.equalsIgnoreCase(src)) return c[1];
                }
            }
            // 无匹配源 → 退化为最新
        }
        // latest（默认）：取 updatedAt 最新的候选
        Object[] win = cands.get(0);
        for (Object[] c : cands) {
            LocalDateTime a = ((DnMdmGoldenRecord) c[0]).getUpdatedAt();
            LocalDateTime b = ((DnMdmGoldenRecord) win[0]).getUpdatedAt();
            if (a != null && (b == null || a.isAfter(b))) win = c;
        }
        return win[1];
    }

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
