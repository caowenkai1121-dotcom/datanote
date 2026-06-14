package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.common.model.R;
import com.datanote.domain.mdm.MdmMatchService;
import com.datanote.domain.mdm.MdmService;
import com.datanote.domain.mdm.mapper.DnMdmAttributeMapper;
import com.datanote.domain.mdm.mapper.DnMdmChangeRequestMapper;
import com.datanote.domain.mdm.mapper.DnMdmDomainMapper;
import com.datanote.domain.mdm.mapper.DnMdmEntityMapper;
import com.datanote.domain.mdm.mapper.DnMdmGoldenRecordMapper;
import com.datanote.domain.mdm.model.DnMdmAttribute;
import com.datanote.domain.mdm.model.DnMdmChangeRequest;
import com.datanote.domain.mdm.model.DnMdmDomain;
import com.datanote.domain.mdm.model.DnMdmEntity;
import com.datanote.domain.mdm.model.DnMdmGoldenRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * MdmCoreController —— 合并自原 3 个 controller(行为不变, 路径保留)。
 */
@RestController
@Tag(name = "主数据-核心", description = "主数据域/实体/属性 + 变更审批 + 数据管家总览")
@RequiredArgsConstructor
public class MdmCoreController {

    private final DnMdmDomainMapper domainMapper;
    private final DnMdmEntityMapper entityMapper;
    private final DnMdmAttributeMapper attributeMapper;
    private final MdmService mdmService;
    private final DnMdmChangeRequestMapper changeMapper;
    private final DnMdmGoldenRecordMapper goldenMapper;
    private final MdmMatchService matchService;
    private final ObjectMapper objectMapper;
    private final com.datanote.platform.notify.NotificationService notificationService;   // 全站#25 审批结果通知

    // ===== 源自 MdmController.java =====
    // ===================== 总览 =====================
    @Operation(summary = "主数据总览统计")
    @GetMapping("/api/mdm/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(mdmService.overview());
    }

    // ===================== 域 =====================
    @Operation(summary = "主数据域列表")
    @GetMapping("/api/mdm/domains")
    public R<List<DnMdmDomain>> listDomains() {
        return R.ok(mdmService.listDomains());
    }

    @Operation(summary = "保存主数据域")
    @PostMapping("/api/mdm/domain/save")
    public R<DnMdmDomain> saveDomain(@RequestBody DnMdmDomain domain) {
        if (domain.getDomainCode() == null || domain.getDomainCode().trim().isEmpty()) {
            throw new BusinessException("域编码不能为空");
        }
        if (domain.getDomainName() == null || domain.getDomainName().trim().isEmpty()) {
            throw new BusinessException("域名称不能为空");
        }
        // 编码唯一校验（排除自身）
        QueryWrapper<DnMdmDomain> qw = new QueryWrapper<>();
        qw.eq("domain_code", domain.getDomainCode().trim());
        if (domain.getId() != null) qw.ne("id", domain.getId());
        if (domainMapper.selectCount(qw) > 0) {
            throw new BusinessException("域编码已存在：" + domain.getDomainCode());
        }
        domain.setDomainCode(domain.getDomainCode().trim());
        if (domain.getStatus() == null) domain.setStatus(1);
        if (domain.getId() != null) {
            domain.setUpdatedAt(LocalDateTime.now());
            domainMapper.updateById(domain);
        } else {
            domain.setCreatedAt(LocalDateTime.now());
            domain.setUpdatedAt(LocalDateTime.now());
            domainMapper.insert(domain);
        }
        return R.ok(domain);
    }

    @Operation(summary = "删除主数据域（级联删除实体与属性）")
    @DeleteMapping("/api/mdm/domain/{id}")
    public R<String> deleteDomain(@PathVariable Long id) {
        mdmService.deleteDomain(id);
        return R.ok("删除成功");
    }

    // ===================== 实体 =====================
    @Operation(summary = "实体列表（按域筛选）")
    @GetMapping("/api/mdm/entities")
    public R<List<DnMdmEntity>> listEntities(@RequestParam(required = false) Long domainId) {
        QueryWrapper<DnMdmEntity> qw = new QueryWrapper<>();
        if (domainId != null) qw.eq("domain_id", domainId);
        qw.orderByDesc("updated_at");
        List<DnMdmEntity> entities = entityMapper.selectList(qw);
        if (entities == null) entities = new ArrayList<>();   // selectList 理论可返回 null
        // 批量回填域名(原逐实体 selectById = N+1)
        List<Long> domIds = new ArrayList<>();
        for (DnMdmEntity e : entities) {
            if (e.getDomainId() != null && !domIds.contains(e.getDomainId())) domIds.add(e.getDomainId());
        }
        if (!domIds.isEmpty()) {
            Map<Long, String> domName = new HashMap<>();
            List<DnMdmDomain> doms = domainMapper.selectBatchIds(domIds);
            if (doms != null) for (DnMdmDomain d : doms) if (d != null && d.getId() != null) domName.put(d.getId(), d.getDomainName());
            for (DnMdmEntity e : entities) {
                if (e.getDomainId() != null) e.setDomainName(domName.get(e.getDomainId()));
            }
        }
        return R.ok(entities);
    }

    @Operation(summary = "保存实体")
    @PostMapping("/api/mdm/entity/save")
    public R<DnMdmEntity> saveEntity(@RequestBody DnMdmEntity entity) {
        if (entity.getDomainId() == null) throw new BusinessException("请先选择所属域");
        if (entity.getEntityCode() == null || entity.getEntityCode().trim().isEmpty()) {
            throw new BusinessException("实体编码不能为空");
        }
        if (entity.getEntityName() == null || entity.getEntityName().trim().isEmpty()) {
            throw new BusinessException("实体名称不能为空");
        }
        if (domainMapper.selectById(entity.getDomainId()) == null) {
            throw new ResourceNotFoundException("所属域");
        }
        // 同域内编码唯一
        QueryWrapper<DnMdmEntity> qw = new QueryWrapper<>();
        qw.eq("domain_id", entity.getDomainId()).eq("entity_code", entity.getEntityCode().trim());
        if (entity.getId() != null) qw.ne("id", entity.getId());
        if (entityMapper.selectCount(qw) > 0) {
            throw new BusinessException("该域下实体编码已存在：" + entity.getEntityCode());
        }
        entity.setEntityCode(entity.getEntityCode().trim());
        if (entity.getStatus() == null) entity.setStatus(1);
        if (entity.getId() != null) {
            entity.setUpdatedAt(LocalDateTime.now());
            entityMapper.updateById(entity);
        } else {
            entity.setAttrCount(0);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            entityMapper.insert(entity);
        }
        return R.ok(entity);
    }

    @Operation(summary = "删除实体（级联删除属性）")
    @DeleteMapping("/api/mdm/entity/{id}")
    public R<String> deleteEntity(@PathVariable Long id) {
        mdmService.deleteEntityCascade(id);
        return R.ok("删除成功");
    }

    // ===================== 属性 =====================
    @Operation(summary = "属性列表（按实体）")
    @GetMapping("/api/mdm/attributes")
    public R<List<DnMdmAttribute>> listAttributes(@RequestParam Long entityId) {
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", entityId).orderByAsc("sort_order").orderByAsc("id");
        return R.ok(attributeMapper.selectList(qw));
    }

    @Operation(summary = "保存属性")
    @PostMapping("/api/mdm/attribute/save")
    public R<DnMdmAttribute> saveAttribute(@RequestBody DnMdmAttribute attr) {
        if (attr.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        if (attr.getAttrCode() == null || attr.getAttrCode().trim().isEmpty()) {
            throw new BusinessException("属性编码不能为空");
        }
        if (attr.getAttrName() == null || attr.getAttrName().trim().isEmpty()) {
            throw new BusinessException("属性名称不能为空");
        }
        if (entityMapper.selectById(attr.getEntityId()) == null) {
            throw new ResourceNotFoundException("所属实体");
        }
        // 同实体内属性编码唯一
        QueryWrapper<DnMdmAttribute> qw = new QueryWrapper<>();
        qw.eq("entity_id", attr.getEntityId()).eq("attr_code", attr.getAttrCode().trim());
        if (attr.getId() != null) qw.ne("id", attr.getId());
        if (attributeMapper.selectCount(qw) > 0) {
            throw new BusinessException("该实体下属性编码已存在：" + attr.getAttrCode());
        }
        attr.setAttrCode(attr.getAttrCode().trim());
        if (attr.getDataType() == null || attr.getDataType().trim().isEmpty()) attr.setDataType("STRING");
        if (attr.getId() != null) {
            attr.setUpdatedAt(LocalDateTime.now());
            attributeMapper.updateById(attr);
        } else {
            attr.setCreatedAt(LocalDateTime.now());
            attr.setUpdatedAt(LocalDateTime.now());
            attributeMapper.insert(attr);
        }
        mdmService.syncAttrCount(attr.getEntityId());
        return R.ok(attr);
    }

    @Operation(summary = "删除属性")
    @DeleteMapping("/api/mdm/attribute/{id}")
    public R<String> deleteAttribute(@PathVariable Long id) {
        DnMdmAttribute attr = attributeMapper.selectById(id);
        attributeMapper.deleteById(id);
        if (attr != null) mdmService.syncAttrCount(attr.getEntityId());
        return R.ok("删除成功");
    }

    // ===== 源自 MdmApprovalController.java =====
    @Operation(summary = "变更请求列表（按状态/实体筛选）")
    @GetMapping("/api/mdm/approval/list")
    public R<List<DnMdmChangeRequest>> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long entityId) {
        QueryWrapper<DnMdmChangeRequest> qw = new QueryWrapper<>();
        if (status != null && !status.isEmpty()) qw.eq("status", status);
        if (entityId != null) qw.eq("entity_id", entityId);
        qw.orderByDesc("updated_at").last("LIMIT 500");
        List<DnMdmChangeRequest> rows = changeMapper.selectList(qw);
        if (rows == null || rows.isEmpty()) return R.ok(rows == null ? new java.util.ArrayList<>() : rows);
        // 批量回填实体名(原逐行 selectById = N+1, 最多 500 次查询)
        java.util.List<Long> eids = new java.util.ArrayList<>();
        for (DnMdmChangeRequest r : rows) {
            if (r.getEntityId() != null && !eids.contains(r.getEntityId())) eids.add(r.getEntityId());
        }
        if (!eids.isEmpty()) {
            java.util.Map<Long, String> nameMap = new java.util.HashMap<>();
            List<DnMdmEntity> _es = entityMapper.selectBatchIds(eids);
            if (_es != null) for (DnMdmEntity e : _es) { // selectList 理论可返回 null
                if (e != null && e.getId() != null) nameMap.put(e.getId(), e.getEntityName());
            }
            for (DnMdmChangeRequest r : rows) {
                if (r.getEntityId() != null) r.setEntityName(nameMap.get(r.getEntityId()));
            }
        }
        return R.ok(rows);
    }

    @Operation(summary = "各状态变更请求计数")
    @GetMapping("/api/mdm/approval/stats")
    public R<Map<String, Object>> stats() {
        // 计数下推到 SQL COUNT, 避免全表物化进内存(原 selectList 全表后内存 filter)
        long pending = changeMapper.selectCount(new QueryWrapper<DnMdmChangeRequest>().eq("status", "pending"));
        long approved = changeMapper.selectCount(new QueryWrapper<DnMdmChangeRequest>().eq("status", "approved"));
        long rejected = changeMapper.selectCount(new QueryWrapper<DnMdmChangeRequest>().eq("status", "rejected"));
        long total = changeMapper.selectCount(new QueryWrapper<>());
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("pending", pending);
        data.put("approved", approved);
        data.put("rejected", rejected);
        return R.ok(data);
    }

    @Operation(summary = "提交变更请求（创建 pending）")
    @PostMapping("/api/mdm/approval/submit")
    public R<DnMdmChangeRequest> submit(@RequestBody DnMdmChangeRequest req) {
        if (req.getEntityId() == null) throw new BusinessException("请先选择所属实体");
        if (entityMapper.selectById(req.getEntityId()) == null) {
            throw new ResourceNotFoundException("所属实体");
        }
        String type = req.getChangeType() == null ? "" : req.getChangeType().trim();
        if (!"create".equals(type) && !"update".equals(type) && !"delete".equals(type)) {
            throw new BusinessException("变更类型须为 create/update/delete");
        }
        if (req.getReason() == null || req.getReason().trim().isEmpty()) {
            throw new BusinessException("变更原因不能为空");
        }
        req.setChangeType(type);
        req.setReason(req.getReason().trim());
        // #19: 申请人身份强制取当前登录用户, 不信任请求体, 否则禁自批守卫(review)可被伪造 requestedBy 绕过
        req.setRequestedBy(currentUser());
        req.setStatus("pending");
        req.setReviewer(null);
        req.setReviewComment(null);
        req.setId(null);
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        changeMapper.insert(req);
        return R.ok(req);
    }

    @Operation(summary = "批准变更请求")
    @Transactional(rollbackFor = Exception.class)   // applyChange 与请求状态更新原子化, 任一失败整体回滚
    @PostMapping("/api/mdm/approval/{id}/approve")
    public R<DnMdmChangeRequest> approve(@PathVariable Long id, @RequestBody(required = false) DnMdmChangeRequest body) {
        return review(id, body, "approved");
    }

    @Operation(summary = "驳回变更请求")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/api/mdm/approval/{id}/reject")
    public R<DnMdmChangeRequest> reject(@PathVariable Long id, @RequestBody(required = false) DnMdmChangeRequest body) {
        return review(id, body, "rejected");
    }

    // ------- 工具 -------
    private R<DnMdmChangeRequest> review(Long id, DnMdmChangeRequest body, String target) {
        DnMdmChangeRequest req = changeMapper.selectById(id);
        if (req == null) throw new ResourceNotFoundException("变更请求");
        if (!"pending".equals(req.getStatus())) {
            throw new BusinessException("仅待审批（pending）的请求可被审批，当前状态：" + req.getStatus());
        }
        // 驳回须有原因(便于申请人修正, 与前端必填一致)
        if ("rejected".equals(target)) {
            String rc = body == null ? null : body.getReviewComment();
            if (rc == null || rc.trim().isEmpty()) throw new BusinessException("驳回必须填写原因");
        }
        // #19: 审批人改取当前登录用户, 不再信任请求体(防冒名审批); 自己提交的申请禁止自批,
        //      admin 用户例外放行(单管理员环境防锁死: 否则 admin 提交的申请无人能批)。
        String reviewer = currentUser();
        if (!"admin".equals(reviewer) && reviewer.equals(req.getRequestedBy())) {
            throw new BusinessException("不能审批自己的变更申请");
        }
        // 并发幂等: 用条件更新 status=pending→target 原子占行, 影响行数为 0 说明已被他人处理,
        //          直接抛错; 这样后续 applyChange 不会被并发重复执行(避免重复黄金记录)。
        int claimed = changeMapper.update(null, new UpdateWrapper<DnMdmChangeRequest>()
                .eq("id", id).eq("status", "pending").set("status", target));
        if (claimed == 0) {
            throw new BusinessException("该变更请求已被处理，请刷新后重试");
        }
        // 关键闭环: 批准即【应用】变更到黄金记录(创建/修改/软删), 否则审批形同虚设。
        // 应用失败(如找不到记录)则抛错, @Transactional 会整体回滚(含上面的占行更新), 请求保持 pending。
        if ("approved".equals(target)) {
            applyChange(req);
        }
        req.setReviewer(reviewer);
        req.setReviewComment(body != null && body.getReviewComment() != null ? body.getReviewComment().trim() : null);
        req.setStatus(target);
        req.setUpdatedAt(LocalDateTime.now());
        changeMapper.updateById(req);
        // 全站#25: 审批终态通知申请人, 铃铛深链主数据中心
        try {
            String requester = req.getRequestedBy();
            if (requester != null && !requester.trim().isEmpty()) {
                String verdict = "approved".equals(target) ? "已批准" : "已驳回";
                notificationService.notify(requester.trim(), "MDM_REVIEW",
                        "主数据变更申请" + verdict + ": " + safeStr(req.getChangeType()) + " (审批人 " + reviewer + ")",
                        "mdm", req.getId(), null);
            }
        } catch (Exception ne) {
            // 通知失败不影响审批结果
        }
        return R.ok(req);
    }

    /** 批准后把变更请求真正应用到黄金记录: create=新建, update=合并 payloadJson, delete=软删(inactive)。 */
    private void applyChange(DnMdmChangeRequest req) {
        LocalDateTime now = LocalDateTime.now();
        String type = req.getChangeType() == null ? "" : req.getChangeType();
        if ("create".equals(type)) {
            String dataJson = req.getPayloadJson() != null && !req.getPayloadJson().trim().isEmpty() ? req.getPayloadJson() : "{}";
            // #18: 与 golden/save 同一道必填/JSON 校验, 不过关即抛错(消息含缺失属性名), 请求保持 pending 不落脏数据
            String calcKey = mdmService.validateGoldenData(req.getEntityId(), dataJson);
            DnMdmGoldenRecord g = new DnMdmGoldenRecord();
            g.setEntityId(req.getEntityId());
            g.setDataJson(dataJson);
            g.setBizKey(req.getBizKey() != null && !req.getBizKey().trim().isEmpty() ? req.getBizKey().trim()
                    : (calcKey != null ? calcKey : ("记录-" + System.currentTimeMillis())));
            g.setStatus("active");
            g.setVersion(1);
            g.setCreatedAt(now);
            g.setUpdatedAt(now);
            goldenMapper.insert(g);
            req.setGoldenRecordId(g.getId());
            mdmService.snapshotGolden(g, "create");   // R128: 审批应用也写历史快照, 否则历史断链
        } else if ("update".equals(type)) {
            DnMdmGoldenRecord g = findGolden(req);
            if (g == null) throw new BusinessException("找不到要修改的黄金记录(bizKey=" + safeStr(req.getBizKey()) + ")，无法应用变更");
            String merged = mergeJson(g.getDataJson(), req.getPayloadJson());
            // #18: 合并后整体校验, 防止变更把必填属性清空/塞入非法 JSON; 失败抛错保持 pending
            mdmService.validateGoldenData(req.getEntityId(), merged);
            g.setDataJson(merged);
            g.setVersion((g.getVersion() == null ? 1 : g.getVersion()) + 1);
            g.setUpdatedAt(now);
            goldenMapper.updateById(g);
            req.setGoldenRecordId(g.getId());
            mdmService.snapshotGolden(g, "update");
        } else if ("delete".equals(type)) {
            DnMdmGoldenRecord g = findGolden(req);
            if (g == null) throw new BusinessException("找不到要删除的黄金记录(bizKey=" + safeStr(req.getBizKey()) + ")，无法应用变更");
            g.setStatus("inactive"); // 软删, 保留可追溯
            g.setUpdatedAt(now);
            goldenMapper.updateById(g);
            req.setGoldenRecordId(g.getId());
            mdmService.snapshotGolden(g, "deactivate");
        }
    }

    /** 定位变更目标黄金记录: 优先 goldenRecordId, 退化 (entityId + bizKey 且未停用)。 */
    private DnMdmGoldenRecord findGolden(DnMdmChangeRequest req) {
        if (req.getGoldenRecordId() != null) {
            DnMdmGoldenRecord g = goldenMapper.selectById(req.getGoldenRecordId());
            // 防越实体改数据: 命中的黄金记录必须归属本变更申请的实体, 否则视为未找到
            if (g != null && Objects.equals(g.getEntityId(), req.getEntityId())) return g;
        }
        if (req.getEntityId() != null && req.getBizKey() != null && !req.getBizKey().trim().isEmpty()) {
            return goldenMapper.selectOne(new QueryWrapper<DnMdmGoldenRecord>()
                    .eq("entity_id", req.getEntityId()).eq("biz_key", req.getBizKey().trim())
                    .ne("status", "inactive").last("LIMIT 1"));
        }
        return null;
    }

    /** 把 patchJson 合并进 baseJson(patch 覆盖同名键); 任一非法则退化为 patch 或 base。 */
    @SuppressWarnings("unchecked")
    private String mergeJson(String baseJson, String patchJson) {
        try {
            Map<String, Object> base = (baseJson == null || baseJson.trim().isEmpty())
                    ? new LinkedHashMap<>() : objectMapper.readValue(baseJson, Map.class);
            if (patchJson != null && !patchJson.trim().isEmpty()) {
                base.putAll(objectMapper.readValue(patchJson, Map.class));
            }
            return objectMapper.writeValueAsString(base);
        } catch (Exception e) {
            return (patchJson != null && !patchJson.trim().isEmpty()) ? patchJson : (baseJson != null ? baseJson : "{}");
        }
    }

    private static String safeStr(String s) { return s == null ? "" : s; }

    /** #19: 当前登录用户(审批人身份), 写法参考 ProjectService.currentUser; 取不到身份按 admin 兜底(鉴权当前开放)。 */
    private static String currentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return auth.getName();
            }
        } catch (Exception ignore) {}
        return "admin";
    }

    // ===== 源自 MdmStewardController.java =====
    @Operation(summary = "数据管家工作台总览（各实体待办聚合）")
    @GetMapping("/api/mdm/steward/overview")
    public R<Map<String, Object>> stewardOverview() {
        List<DnMdmEntity> entities = entityMapper.selectList(null);
        if (entities == null) entities = new ArrayList<>();   // selectList 理论可返回 null
        // 域名映射
        Map<Long, String> domainName = new HashMap<>();
        List<DnMdmDomain> _ds = domainMapper.selectList(null);
        if (_ds != null) for (DnMdmDomain d : _ds) domainName.put(d.getId(), d.getDomainName()); // selectList 理论可返回 null

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalDraft = 0, totalActive = 0, totalDupClusters = 0, totalDupRecords = 0;
        for (DnMdmEntity e : entities) {
            List<DnMdmGoldenRecord> recs = goldenMapper.selectList(
                    new QueryWrapper<DnMdmGoldenRecord>().eq("entity_id", e.getId()));
            if (recs == null) recs = new ArrayList<>();   // selectList 理论可返回 null
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
