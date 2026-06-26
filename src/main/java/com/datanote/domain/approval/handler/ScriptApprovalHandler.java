package com.datanote.domain.approval.handler;

import com.datanote.domain.approval.ApprovalApplyHandler;
import com.datanote.domain.approval.model.DnApproval;
import com.datanote.domain.develop.ScriptApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 脚本上线审批 apply: 复用 ScriptApprovalService.review(无环: 不反向依赖 ApprovalService)。 */
@Component
@RequiredArgsConstructor
public class ScriptApprovalHandler implements ApprovalApplyHandler {
    public static final String FLOW = "SCRIPT_CHANGE";
    private final ScriptApprovalService scriptApprovalService;

    @Override
    public String flowType() { return FLOW; }

    @Override
    public void onApproved(DnApproval a) {
        scriptApprovalService.review(Long.valueOf(a.getBizId()), "approved", a.getReviewComment());
    }

    @Override
    public void onRejected(DnApproval a) {
        String c = a.getReviewComment() == null || a.getReviewComment().trim().isEmpty() ? "驳回" : a.getReviewComment();
        scriptApprovalService.review(Long.valueOf(a.getBizId()), "rejected", c);
    }
}
