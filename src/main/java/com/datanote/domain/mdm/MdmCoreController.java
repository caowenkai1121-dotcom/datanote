package com.datanote.domain.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
        // 填充域名称
        for (DnMdmEntity e : entities) {
            DnMdmDomain d = domainMapper.selectById(e.getDomainId());
            if (d != null) e.setDomainName(d.getDomainName());
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
        // 填充实体名称
        for (DnMdmChangeRequest r : rows) {
            if (r.getEntityId() != null) {
                DnMdmEntity e = entityMapper.selectById(r.getEntityId());
                if (e != null) r.setEntityName(e.getEntityName());
            }
        }
        return R.ok(rows);
    }

    @Operation(summary = "各状态变更请求计数")
    @GetMapping("/api/mdm/approval/stats")
    public R<Map<String, Object>> stats() {
        List<DnMdmChangeRequest> all = changeMapper.selectList(new QueryWrapper<>());
        Map<String, Object> data = new HashMap<>();
        long pending = all.stream().filter(r -> "pending".equals(r.getStatus())).count();
        long approved = all.stream().filter(r -> "approved".equals(r.getStatus())).count();
        long rejected = all.stream().filter(r -> "rejected".equals(r.getStatus())).count();
        data.put("total", all.size());
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
    @PostMapping("/api/mdm/approval/{id}/approve")
    public R<DnMdmChangeRequest> approve(@PathVariable Long id, @RequestBody(required = false) DnMdmChangeRequest body) {
        return review(id, body, "approved");
    }

    @Operation(summary = "驳回变更请求")
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
        String reviewer = body != null && body.getReviewer() != null ? body.getReviewer().trim() : "";
        if (reviewer.isEmpty()) throw new BusinessException("审批人不能为空");
        req.setReviewer(reviewer);
        req.setReviewComment(body != null && body.getReviewComment() != null ? body.getReviewComment().trim() : null);
        req.setStatus(target);
        req.setUpdatedAt(LocalDateTime.now());
        changeMapper.updateById(req);
        return R.ok(req);
    }

    // ===== 源自 MdmStewardController.java =====
    @Operation(summary = "数据管家工作台总览（各实体待办聚合）")
    @GetMapping("/api/mdm/steward/overview")
    public R<Map<String, Object>> stewardOverview() {
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
