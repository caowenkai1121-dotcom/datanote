package com.datanote.domain.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.approval.model.DnApproval;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 统一审批服务: 各业务流提交即建审批记录, 统一状态机。允许自审自批(权限由拦截器把关)。
 *  apply 在审批的 HTTP 线程内同步派发各流 handler(复用现有 apply 逻辑, 保留 SecurityContext); 事件再经 Redis Streams 异步通知/审计。 */
@Slf4j
@Service
public class ApprovalService {
    private final DnApprovalMapper mapper;
    private final ApprovalEventService eventService;
    private final Map<String, ApprovalApplyHandler> handlers = new HashMap<>();

    public ApprovalService(DnApprovalMapper mapper, ApprovalEventService eventService, List<ApprovalApplyHandler> applyHandlers) {
        this.mapper = mapper;
        this.eventService = eventService;
        if (applyHandlers != null) for (ApprovalApplyHandler h : applyHandlers) if (h != null && h.flowType() != null) handlers.put(h.flowType(), h);
    }

    /** 提交审批: 建 PENDING 记录并发 SUBMITTED 事件。 */
    public DnApproval submit(String flowType, String bizId, String title, String submitter, String payloadJson) {
        DnApproval a = new DnApproval();
        a.setFlowType(flowType);
        a.setBizId(bizId);
        a.setTitle(title);
        a.setSubmitter(submitter);
        a.setStatus("PENDING");
        a.setPayloadJson(payloadJson);
        a.setCreatedAt(LocalDateTime.now());
        mapper.insert(a);
        eventService.publish(a.getId(), "SUBMITTED");
        return a;
    }

    /** 审批通过/驳回。允许自审自批: 不校验 reviewer==submitter(权限由 PermInterceptor 把关)。 */
    public DnApproval review(Long id, boolean approve, String reviewer, String comment) {
        DnApproval a = mapper.selectById(id);
        if (a == null) throw new IllegalStateException("审批记录不存在: " + id);
        if (!"PENDING".equals(a.getStatus())) throw new IllegalStateException("该审批已处理: " + a.getStatus());
        a.setStatus(approve ? "APPROVED" : "REJECTED");
        a.setReviewer(reviewer);
        a.setReviewComment(comment);
        a.setReviewedAt(LocalDateTime.now());
        mapper.updateById(a);
        // 同步派发各流 apply(HTTP 线程内, 保留 SecurityContext + 复用现有逻辑)。apply 失败回滚状态, 避免"审批通过但未生效"。
        ApprovalApplyHandler h = handlers.get(a.getFlowType());
        if (h != null) {
            try {
                if (approve) h.onApproved(a); else h.onRejected(a);
            } catch (RuntimeException e) {
                a.setStatus("PENDING"); a.setReviewer(null); a.setReviewedAt(null); mapper.updateById(a); // 回滚, 保持待审
                log.warn("审批 apply 失败, 已回滚为待审: id={} flow={} err={}", a.getId(), a.getFlowType(), e.getMessage());
                throw e;
            }
        }
        eventService.publish(a.getId(), approve ? "APPROVED" : "REJECTED"); // 事件: 通知/审计
        return a;
    }

    public List<DnApproval> listPending() {
        return mapper.selectList(new LambdaQueryWrapper<DnApproval>()
                .eq(DnApproval::getStatus, "PENDING").orderByDesc(DnApproval::getCreatedAt).last("LIMIT 500"));
    }

    public List<DnApproval> list(String status, String flowType) {
        LambdaQueryWrapper<DnApproval> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) qw.eq(DnApproval::getStatus, status);
        if (flowType != null && !flowType.isEmpty()) qw.eq(DnApproval::getFlowType, flowType);
        qw.orderByDesc(DnApproval::getCreatedAt).last("LIMIT 500");
        return mapper.selectList(qw);
    }

    public DnApproval get(Long id) { return mapper.selectById(id); }

    /** 按底层业务记录查最近一条审批(供各流防重复提交/状态联动)。 */
    public DnApproval findByBiz(String flowType, String bizId) {
        return mapper.selectOne(new LambdaQueryWrapper<DnApproval>()
                .eq(DnApproval::getFlowType, flowType).eq(DnApproval::getBizId, bizId)
                .orderByDesc(DnApproval::getId).last("LIMIT 1"));
    }
}
