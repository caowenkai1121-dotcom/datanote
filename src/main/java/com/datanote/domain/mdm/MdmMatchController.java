package com.datanote.domain.mdm;

import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.MdmMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主数据匹配去重 Controller —— 基于实体关键/唯一属性检测重复黄金记录，并支持合并(保留存活记录)。
 */
@RestController
@RequestMapping("/api/mdm/match")
@Tag(name = "主数据匹配去重", description = "重复黄金记录检测与合并")
@RequiredArgsConstructor
public class MdmMatchController {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final MdmMatchService matchService;

    @Operation(summary = "检测重复黄金记录（按关键/唯一属性聚类）")
    @GetMapping("/duplicates")
    public R<Map<String, Object>> duplicates(@RequestParam Long entityId) {
        return R.ok(matchService.detectDuplicates(entityId));
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

        // 收集被合并记录
        List<DnMdmGoldenRecord> mergedRecs = new ArrayList<>();
        for (Object ido : (List<?>) mObj) {
            Long mid = Long.valueOf(String.valueOf(ido));
            if (mid.equals(survivorId)) continue;
            DnMdmGoldenRecord m = goldenMapper.selectById(mid);
            if (m != null) mergedRecs.add(m);
        }
        // 应用存活性规则：存活记录 + 被合并记录全部参与字段级选优，组合最佳值写入存活记录
        List<DnMdmGoldenRecord> all = new ArrayList<>();
        all.add(survivor);
        all.addAll(mergedRecs);
        List<String> applied = matchService.applySurvivorship(survivor.getEntityId(), survivor, all);
        // 停用被合并记录
        for (DnMdmGoldenRecord m : mergedRecs) {
            m.setStatus("inactive");
            m.setUpdatedAt(LocalDateTime.now());
            goldenMapper.updateById(m);
        }
        // 存活记录（已含存活性组合后的 data_json）置为生效并升版本
        survivor.setStatus("active");
        survivor.setVersion((survivor.getVersion() == null ? 1 : survivor.getVersion()) + 1);
        survivor.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(survivor);

        Map<String, Object> data = new HashMap<>();
        data.put("survivorId", survivorId);
        data.put("mergedCount", mergedRecs.size());
        data.put("survivorshipApplied", applied);
        return R.ok(data);
    }
}
