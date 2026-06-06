package com.datanote.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnMdmDomainMapper;
import com.datanote.mapper.DnMdmEntityMapper;
import com.datanote.mapper.DnMdmGoldenRecordMapper;
import com.datanote.model.DnMdmDomain;
import com.datanote.model.DnMdmEntity;
import com.datanote.model.DnMdmGoldenRecord;
import com.datanote.model.R;
import com.datanote.service.MdmMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 主数据管家工作台 Controller —— 聚合各实体待处理事项（待复核草稿记录、待去重重复簇），
 * 为数据管家提供统一待办清单与直达入口。
 */
@RestController
@RequestMapping("/api/mdm/steward")
@Tag(name = "主数据管家工作台", description = "数据管家待办聚合")
@RequiredArgsConstructor
public class MdmStewardController {

    private final DnMdmEntityMapper entityMapper;
    private final DnMdmDomainMapper domainMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;
    private final MdmMatchService matchService;

    @Operation(summary = "数据管家工作台总览（各实体待办聚合）")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        List<DnMdmEntity> entities = entityMapper.selectList(null);
        // 域名映射
        Map<Long, String> domainName = new HashMap<>();
        for (DnMdmDomain d : domainMapper.selectList(null)) domainName.put(d.getId(), d.getDomainName());

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalDraft = 0, totalActive = 0, totalDupClusters = 0, totalDupRecords = 0;
        for (DnMdmEntity e : entities) {
            List<DnMdmGoldenRecord> recs = goldenMapper.selectList(
                    new QueryWrapper<DnMdmGoldenRecord>().eq("entity_id", e.getId()));
            long draft = recs.stream().filter(r -> "draft".equals(r.getStatus())).count();
            long active = recs.stream().filter(r -> "active".equals(r.getStatus())).count();

            Map<String, Object> dup = matchService.detectDuplicates(e.getId());
            int dupClusters = ((Number) dup.getOrDefault("clusterCount", 0)).intValue();
            int dupRecords = ((Number) dup.getOrDefault("duplicateRecordCount", 0)).intValue();

            totalDraft += draft; totalActive += active; totalDupClusters += dupClusters; totalDupRecords += dupRecords;

            // 仅收录有待办的实体（草稿>0 或 重复簇>0），避免清单噪音
            if (draft > 0 || dupClusters > 0) {
                Map<String, Object> row = new HashMap<>();
                row.put("entityId", e.getId());
                row.put("entityName", e.getEntityName());
                row.put("domainName", domainName.getOrDefault(e.getDomainId(), ""));
                row.put("draftCount", draft);
                row.put("activeCount", active);
                row.put("dupClusterCount", dupClusters);
                row.put("dupRecordCount", dupRecords);
                row.put("pendingTotal", draft + dupClusters);
                rows.add(row);
            }
        }
        rows.sort((a, b) -> ((Long) b.get("pendingTotal")).compareTo((Long) a.get("pendingTotal")));

        Map<String, Object> data = new HashMap<>();
        data.put("entityCount", entities.size());
        data.put("totalDraft", totalDraft);
        data.put("totalActive", totalActive);
        data.put("totalDupClusters", totalDupClusters);
        data.put("totalDupRecords", totalDupRecords);
        data.put("pendingEntityCount", rows.size());
        data.put("todos", rows);
        return R.ok(data);
    }
}
