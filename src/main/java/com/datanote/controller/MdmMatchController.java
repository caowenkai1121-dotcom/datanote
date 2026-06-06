package com.datanote.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.exception.BusinessException;
import com.datanote.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmAttributeMapper;
import com.datanote.mapper.DnMdmGoldenRecordMapper;
import com.datanote.model.DnMdmAttribute;
import com.datanote.model.DnMdmGoldenRecord;
import com.datanote.model.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 主数据匹配去重 Controller —— 基于实体关键/唯一属性检测重复黄金记录，并支持合并(保留存活记录)。
 */
@RestController
@RequestMapping("/api/mdm/match")
@Tag(name = "主数据匹配去重", description = "重复黄金记录检测与合并")
@RequiredArgsConstructor
public class MdmMatchController {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "检测重复黄金记录（按关键/唯一属性聚类）")
    @GetMapping("/duplicates")
    public R<Map<String, Object>> duplicates(@RequestParam Long entityId) {
        // 关键/唯一属性作为匹配键
        QueryWrapper<DnMdmAttribute> aqw = new QueryWrapper<>();
        aqw.eq("entity_id", entityId).and(w -> w.eq("is_key", 1).or().eq("is_unique", 1));
        List<DnMdmAttribute> keyAttrs = attributeMapper.selectList(aqw);

        // 仅对生效/草稿记录去重（已停用的不参与）
        QueryWrapper<DnMdmGoldenRecord> gqw = new QueryWrapper<>();
        gqw.eq("entity_id", entityId).ne("status", "inactive");
        List<DnMdmGoldenRecord> records = goldenMapper.selectList(gqw);

        List<Map<String, Object>> clusters = new ArrayList<>();
        Set<String> seenClusterKey = new HashSet<>();   // 防同一组记录被多属性重复报出

        for (DnMdmAttribute attr : keyAttrs) {
            Map<String, List<DnMdmGoldenRecord>> byVal = new LinkedHashMap<>();
            for (DnMdmGoldenRecord r : records) {
                Map<String, Object> vals = parse(r.getDataJson());
                Object v = vals.get(attr.getAttrCode());
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
                cluster.put("matchValue", e.getValue().get(0) != null
                        ? parse(e.getValue().get(0).getDataJson()).get(attr.getAttrCode()) : e.getKey());
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
        return R.ok(data);
    }

    @Operation(summary = "合并重复记录（保留存活记录，其余置为停用）")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/merge")
    public R<Map<String, Object>> merge(@RequestBody Map<String, Object> body) {
        Object sObj = body.get("survivorId");
        Object mObj = body.get("mergedIds");
        if (sObj == null) throw new BusinessException("请指定存活记录");
        Long survivorId = Long.valueOf(String.valueOf(sObj));
        DnMdmGoldenRecord survivor = goldenMapper.selectById(survivorId);
        if (survivor == null) throw new ResourceNotFoundException("存活记录");
        if (!(mObj instanceof List)) throw new BusinessException("请指定被合并记录");

        int merged = 0;
        for (Object ido : (List<?>) mObj) {
            Long mid = Long.valueOf(String.valueOf(ido));
            if (mid.equals(survivorId)) continue;
            DnMdmGoldenRecord m = goldenMapper.selectById(mid);
            if (m == null) continue;
            m.setStatus("inactive");
            m.setUpdatedAt(LocalDateTime.now());
            goldenMapper.updateById(m);
            merged++;
        }
        // 存活记录置为生效并升版本
        survivor.setStatus("active");
        survivor.setVersion((survivor.getVersion() == null ? 1 : survivor.getVersion()) + 1);
        survivor.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(survivor);

        Map<String, Object> data = new HashMap<>();
        data.put("survivorId", survivorId);
        data.put("mergedCount", merged);
        return R.ok(data);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return new HashMap<>(); }
    }
}
