package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenHistoryMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.mapper.DnMdmSurvivorshipRuleMapper;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenHistory;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.datanote.domain.mdm.model.DnMdmSurvivorshipRule;
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
    private final DnMdmGoldenHistoryMapper historyMapper;   // R126 变更历史快照
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmSurvivorshipRuleMapper ruleMapper;
    private final MdmMatchService matchService;
    private final MdmPublishService mdmPublishService;   // R32 黄金记录发布→自动扇出订阅
    private final MdmService mdmService;                 // R128 快照逻辑收敛到 service 共用

    // ===== 源自 MdmGoldenController.java =====
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
        // 用 DB count 精确统计, 不复用带 LIMIT 500 的 list(), 避免超 500 条时统计失真
        Map<String, Object> data = new HashMap<>();
        data.put("total", countByStatus(entityId, null));
        data.put("active", countByStatus(entityId, "active"));
        data.put("draft", countByStatus(entityId, "draft"));
        data.put("inactive", countByStatus(entityId, "inactive"));
        return R.ok(data);
    }

    private long countByStatus(Long entityId, String status) {
        QueryWrapper<DnMdmGoldenRecord> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId);
        if (status != null) qw.eq("status", status);
        Long c = goldenMapper.selectCount(qw);
        return c == null ? 0 : c;
    }

    @Operation(summary = "保存黄金记录（含必填属性校验）")
    @PostMapping("/api/mdm/golden/save")
    public R<DnMdmGoldenRecord> save(@RequestBody DnMdmGoldenRecord rec) {
        if (rec.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        DnMdmEntity entity = entityMapper.selectById(rec.getEntityId());
        if (entity == null) throw new ResourceNotFoundException("所属实体");

        // #18: 必填/JSON 校验 + 业务主键计算收敛到 MdmService, 与审批 applyChange 共用同一道门禁
        String bizKey = mdmService.validateGoldenData(rec.getEntityId(), rec.getDataJson());
        rec.setBizKey(bizKey != null ? bizKey : ("记录-" + System.currentTimeMillis()));
        if (rec.getStatus() == null || rec.getStatus().isEmpty()) rec.setStatus("draft");

        if (rec.getId() != null) {
            DnMdmGoldenRecord old = goldenMapper.selectById(rec.getId());
            if (old == null) throw new ResourceNotFoundException("黄金记录");   // 防对不存在记录静默空更新却谎报成功
            // #19: 已生效(active)存量记录禁止直改, 防绕过审批; 草稿/停用仍可直接编辑
            if ("active".equals(old.getStatus())) {
                throw new BusinessException("已生效记录请走变更申请");
            }
            rec.setVersion((old != null && old.getVersion() != null ? old.getVersion() : 1) + 1);
            rec.setUpdatedAt(LocalDateTime.now());
            goldenMapper.updateById(rec);
            snapshot(rec, "update");
        } else {
            rec.setVersion(1);
            rec.setCreatedAt(LocalDateTime.now());
            rec.setUpdatedAt(LocalDateTime.now());
            goldenMapper.insert(rec);
            snapshot(rec, "create");
        }
        return R.ok(rec);
    }

    @Operation(summary = "黄金记录变更历史（新→旧）")
    @GetMapping("/api/mdm/golden/{id}/history")
    public R<List<DnMdmGoldenHistory>> history(@PathVariable Long id) {
        QueryWrapper<DnMdmGoldenHistory> qw = new QueryWrapper<>();
        qw.eq("golden_id", id).orderByDesc("id").last("LIMIT 100");
        return R.ok(historyMapper.selectList(qw));
    }

    @Operation(summary = "发布黄金记录（草稿→生效）")
    @PostMapping("/api/mdm/golden/{id}/publish")
    public R<DnMdmGoldenRecord> publish(@PathVariable Long id) {
        DnMdmGoldenRecord rec = goldenMapper.selectById(id);
        if (rec == null) throw new ResourceNotFoundException("黄金记录");
        rec.setStatus("active");
        rec.setUpdatedAt(LocalDateTime.now());
        goldenMapper.updateById(rec);
        snapshot(rec, "publish");
        // R32 闭环: 发布即自动向匹配订阅扇出(update 事件), 不再依赖手动 pubsub/publish
        // R127: 扇出含逐订阅 HTTP 推送(各3s超时), 同步会拖死发布响应 — 改后台异步, 推送结果照落 publish_log 可查
        final DnMdmGoldenRecord pubRec = rec;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { mdmPublishService.fanOut(pubRec, "update"); } catch (Exception ignore) {}
        });
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
        snapshot(rec, "deactivate");
        return R.ok(rec);
    }

    @Operation(summary = "删除黄金记录")
    @DeleteMapping("/api/mdm/golden/{id}")
    public R<String> delete(@PathVariable Long id) {
        goldenMapper.deleteById(id);
        return R.ok("删除成功");
    }

    // ------- 工具 -------
    /** R126: 写一条变更后快照（R128 收敛到 MdmService 与审批应用链路共用） */
    private void snapshot(DnMdmGoldenRecord rec, String changeType) {
        mdmService.snapshotGolden(rec, changeType);
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
        Long survivorId;
        try {
            survivorId = Long.valueOf(String.valueOf(sObj));
        } catch (NumberFormatException e) {
            throw new BusinessException("存活记录 ID 格式错误");
        }
        DnMdmGoldenRecord survivor = goldenMapper.selectById(survivorId);
        if (survivor == null) throw new ResourceNotFoundException("存活记录");
        if (!(mObj instanceof List)) throw new BusinessException("请指定被合并记录");

        // 收集被合并记录
        List<DnMdmGoldenRecord> mergedRecs = new ArrayList<>();
        for (Object ido : (List<?>) mObj) {
            Long mid;
            try {
                mid = Long.valueOf(String.valueOf(ido));
            } catch (NumberFormatException e) {
                throw new BusinessException("被合并记录 ID 格式错误");
            }
            if (mid.equals(survivorId)) continue;
            DnMdmGoldenRecord m = goldenMapper.selectById(mid);
            if (m == null) continue;
            // 防 IDOR + 跨实体脏数据污染: 被合并记录必须与存活记录同属一个实体
            if (!java.util.Objects.equals(m.getEntityId(), survivor.getEntityId())) {
                throw new BusinessException("被合并记录与存活记录不属于同一实体");
            }
            mergedRecs.add(m);
        }
        // 防空合并: 被合并集为空(全是自身/不存在)时不应空跑——否则只是无意义的版本号自增+误导审计快照
        if (mergedRecs.isEmpty()) throw new BusinessException("没有有效的被合并记录, 无法执行合并");
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
        snapshot(survivor, "merge");

        Map<String, Object> data = new HashMap<>();
        data.put("survivorId", survivorId);
        data.put("mergedCount", mergedRecs.size());
        data.put("survivorshipApplied", applied);
        return R.ok(data);
    }
}
