package com.datanote.domain.approval.handler;

import com.datanote.domain.approval.ApprovalApplyHandler;
import com.datanote.domain.approval.model.DnApproval;
import com.datanote.domain.mdm.MdmCoreController;
import com.datanote.domain.mdm.model.DnMdmChangeRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** 主数据变更审批 apply: 复用 MdmCoreController.approve/reject(apply 逻辑在 controller)。
 *  @Lazy 注入打破 ApprovalService→handler→controller→ApprovalService 的 Spring 循环依赖。
 *  在审批 HTTP 线程内同步调用, 保留 SecurityContext(reviewer 取当前登录用户)。 */
@Component
public class MdmApprovalHandler implements ApprovalApplyHandler {
    public static final String FLOW = "MDM_CHANGE";
    private final MdmCoreController mdmController;

    public MdmApprovalHandler(@Lazy MdmCoreController mdmController) {
        this.mdmController = mdmController;
    }

    @Override
    public String flowType() { return FLOW; }

    @Override
    public void onApproved(DnApproval a) {
        DnMdmChangeRequest body = new DnMdmChangeRequest();
        body.setReviewComment(a.getReviewComment());
        mdmController.approve(Long.valueOf(a.getBizId()), body);
    }

    @Override
    public void onRejected(DnApproval a) {
        DnMdmChangeRequest body = new DnMdmChangeRequest();
        String c = a.getReviewComment() == null || a.getReviewComment().trim().isEmpty() ? "驳回" : a.getReviewComment();
        body.setReviewComment(c);
        mdmController.reject(Long.valueOf(a.getBizId()), body);
    }
}
