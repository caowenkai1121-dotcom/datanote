package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.MdmMatchService;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmSurvivorshipRuleMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmSurvivorshipRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * MdmGoldenController —— 合并自原 3 个 controller(行为不变, 路径保留)。
 */
@RestController
@Tag(name = "主数据-黄金记录", description = "黄金记录 + 存活策略 + 匹配合并")
@RequiredArgsConstructor
public class MdmGoldenController {

    private final DnMdmGoldenRecordMapper goldenMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmSurvivorshipRuleMapper ruleMapper;
    private final MdmMatchService matchService;

    // ===== 源自 MdmGoldenController.java =====
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "黄金记录列表（按实体）")
    @GetMapping("/api/mdm/golden/list")
    public R<List<DnMdmGoldenRecord>> list(@RequestParam Long entityId,
                                           @RequestParam(required = false) String status) {
        QueryWrapper<DnMdmGoldenRecord> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId);
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        qw.orderByDesc("updated_at").last("LIMIT 500");
        return R.ok(goldenMapper.selectList(qw));
    }

    @Operation(summary = "黄金记录详情")
    @GetMapping("/api/mdm/golden/{id}")
    public R<DnMdmGoldenRecord> get(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        return R.ok(rec);
    }

    @Operation(summary = "各状态统计（按实体）")
    @GetMapping("/api/mdm/golden/stats")
    public R<Map<String, Object>> stats(@RequestParam Long entityId) {
        List<DnMdmGoldenRecord> all = list(entityId, null).getData();
        if (all == null) all = new java.util.ArrayList<>();
        Map<String, Object> data = new HashMap<>();
        long active = all.stream().filter(r -> "active".equals(r.getStatus())).count();
        long draft = all.stream().filter(r -> "draft".equals(r.getStatus())).count();
        long inactive = all.stream().filter(r -> "inactive".equals(r.getStatus())).count();
        data.put("total", all.size());
        data.put("active", active);
        data.put("draft", draft);
        data.put("inactive", inactive);
        return R.ok(data);
    }

    @Operation(summary = "保存黄金记录（含必填属性校验）")
    @PostMapping("/api/mdm/golden/save")
    public R<DnMdmGoldenRecord> save(@RequestBody DnMdmGoldenRecord rec) {
        if (rec.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        DnMdmEntity entity = entityMapper.selectById(rec.getEntityId());
        if (entity == null) throw new ResourceNotFoundException("所属实体");

        // 解析属性值 JSON
        Map<String, Object> values = parseJson(rec.getDataJson());

        // 加载实体属性，做必填校验 + 计算业务主键
        List<DnMdmAttribute> attrs = loadAttrs(rec.getEntityId());
        String bizKey = null;
        for (DnMdmAttribute a : attrs) {
            Object v = values.get(a.getAttrCode());
            boolean empty = (v == null || String.valueOf(v).trim().isEmpty());
            if (a.getRequired() != null && a.getRequired() == 1 && empty) {
                throw new BusinessException("必填属性未填写：" + a.getAttrName());
            }
            // 业务主键优先取关键字段，其次唯一字段的首个非空值
            if (bizKey == null && !empty && ((a.getIsKey() != null && a.getIsKey() == 1)
                    || (a.getIsUnique() != null && a.getIsUnique() == 1))) {
                bizKey = String.valueOf(v);
            }
        }
        if (bizKey == null) {
            // 退化：取第一个非空属性值
            for (DnMdmAttribute a : attrs) {
                Object v = values.get(a.getAttrCode());
                if (v != null && !String.valueOf(v).trim().isEmpty()) { bizKey = String.valueOf(v); break; }
            }
        }
        rec.setBizKey(bizKey != null ? bizKey : ("记录-" + System.currentTimeMillis()));
        if (rec.getStatus() == null || rec.getStatus().isEmpty()) rec.setStatus("draft");

        if (rec.getId() != null) {
            DnMdmGoldenRecord old = goldenMapper.selectById(rec.getId());
            rec.setVersion((old != null && old.getVersion() != null ? old.getVersion() : 1) + 1);
            rec.setUpdatedAt(LocalDateTime.now());
            goldenMapper.updateById(rec);
        } else {
            rec.setVersion(1);
            rec.setCreatedAt(LocalDateTime.now());
            rec.setUpdatedAt(LocalDateTime.now());
            goldenMapper.insert(rec);
        }
        return R.ok(rec);
    }

    @Operation(summary = "发布黄金记录（草稿→生效）")
    @PostMapping("/api/mdm/golden/{id}/publish")
    public R<DnMdmGoldenRecord> publish(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        rec.setStatus("active");
        rec.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(rec);
        return R.ok(rec);
    }

    @Operation(summary = "停用黄金记录")
    @PostMapping("/api/mdm/golden/{id}/deactivate")
    public R<DnMdmGoldenRecord> deactivate(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        rec.setStatus("inactive");
        rec.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(rec);
        return R.ok(rec);
    }

    @Operation(summary = "删除黄金记录")
    @DeleteMapping("/api/mdm/golden/{id}")
    public R<String> delete(@PathVariable Long id) {
        goldenMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ------- 工具 -------
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException("属性值格式错误（非合法 JSON）");
        }
    }

    private List<DnMdmAttribute> loadAttrs(Long entityId) {
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("sort_order").orderByAsc("id");
        return attributeMapper.selectList(qw);
    }

    // ===== 源自 MdmSurvivorshipController.java =====
    /** 可选存活策略枚举（与前端下拉一致） */
    private static final List<String> STRATEGIES = Arrays.asList("latest", "most_complete", "source_priority");

    @Operation(summary = "存活策略枚举说明")
    @GetMapping("/api/mdm/survivorship/strategies")
    public R<List<Map<String, String>>> strategies() {
        return R.ok(Arrays.asList(
                Map.of("value", "latest", "label", "最新值", "desc", "取更新时间最新的来源值"),
                Map.of("value", "most_complete", "label", "最完整", "desc", "取非空且长度最长（信息最完整）的值"),
                Map.of("value", "source_priority", "label", "源系统优先", "desc", "按指定源系统优先级清单依次取值")
        ));
    }

    @Operation(summary = "存活性规则列表（按实体）")
    @GetMapping("/api/mdm/survivorship/list")
    public R<List<DnMdmSurvivorshipRule>> survivorshipList(@RequestParam Long entityId) {
        QueryWrapper<DnMdmSurvivorshipRule> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("priority").orderByAsc("id");
        return R.ok(ruleMapper.selectList(qw));
    }

    @Operation(summary = "保存存活性规则")
    @PostMapping("/api/mdm/survivorship/save")
    public R<DnMdmSurvivorshipRule> survivorshipSave(@RequestBody DnMdmSurvivorshipRule rule) {
        if (rule.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        if (rule.getAttrCode() == null || rule.getAttrCode().trim().isEmpty()) {
            throw new BusinessException("属性编码不能为空");
        }
        if (entityMapper.selectById(rule.getEntityId()) == null) {
            throw new ResourceNotFoundException("所属实体");
        }
        if (rule.getStrategy() == null || !STRATEGIES.contains(rule.getStrategy())) {
            throw new BusinessException("存活策略无效，应为 latest / most_complete / source_priority");
        }
        // 源优先策略必须提供源系统优先级清单
        if ("source_priority".equals(rule.getStrategy())
                && (rule.getSourcePriority() == null || rule.getSourcePriority().trim().isEmpty())) {
            throw new BusinessException("「源系统优先」策略需填写源系统优先级清单");
        }
        // 同实体内属性唯一（UNIQUE entity_id + attr_code），排除自身
        QueryWrapper<DnMdmSurvivorshipRule> qw = new QueryWrapper<>();
        qw.eq("entity_id", rule.getEntityId()).eq("attr_code", rule.getAttrCode().trim());
        if (rule.getId() != null) qw.ne("id", rule.getId());
        if (ruleMapper.selectCount(qw) > 0) {
            throw new BusinessException("该实体下属性已配置存活规则：" + rule.getAttrCode());
        }
        rule.setAttrCode(rule.getAttrCode().trim());
        if (rule.getSourcePriority() != null) rule.setSourcePriority(rule.getSourcePriority().trim());
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getId() != null) {
            rule.setUpdatedAt(LocalDateTime.now());
            ruleMapper.updateById(rule);
        } else {
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
            ruleMapper.insert(rule);
        }
        return R.ok(rule);
    }

    @Operation(summary = "删除存活性规则")
    @DeleteMapping("/api/mdm/survivorship/{id}")
    public R<String> survivorshipDelete(@PathVariable Long id) {
        ruleMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ===== 源自 MdmMatchController.java =====
    @Operation(summary = "检测重复黄金记录（按关键/唯一属性聚类）")
    @GetMapping("/api/mdm/match/duplicates")
    public R<Map<String, Object>> duplicates(@RequestParam Long entityId) {
        return R.ok(matchService.detectDuplicates(entityId));
    }

    @Operation(summary = "合并重复记录（保留存活记录，其余置为停用）")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/api/mdm/match/merge")
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
