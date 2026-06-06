package com.datanote.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.exception.BusinessException;
import com.datanote.exception.ResourceNotFoundException;
import com.datanote.mapper.DnMdmChangeRequestMapper;
import com.datanote.mapper.DnMdmEntityMapper;
import com.datanote.model.DnMdmChangeRequest;
import com.datanote.model.DnMdmEntity;
import com.datanote.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主数据变更审批 Controller —— 对黄金记录变更（新增/修改/删除）发起审批流，审批人批准/驳回。
 */
@RestController
@RequestMapping("/api/mdm/approval")
@Tag(name = "主数据变更审批", description = "黄金记录变更请求的提交、审批与驳回")
@RequiredArgsConstructor
public class MdmApprovalController {

    private final DnMdmChangeRequestMapper changeMapper;
    private final DnMdmEntityMapper entityMapper;

    @Operation(summary = "变更请求列表（按状态/实体筛选）")
    @GetMapping("/list")
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
    @GetMapping("/stats")
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
    @PostMapping("/submit")
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
    @PostMapping("/{id}/approve")
    public R<DnMdmChangeRequest> approve(@PathVariable Long id, @RequestBody(required = false) DnMdmChangeRequest body) {
        return review(id, body, "approved");
    }

    @Operation(summary = "驳回变更请求")
    @PostMapping("/{id}/reject")
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
}
