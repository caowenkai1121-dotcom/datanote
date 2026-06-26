package com.datanote.domain.approval.handler;

import com.datanote.domain.approval.ApprovalApplyHandler;
import com.datanote.domain.approval.model.DnApproval;
import com.datanote.domain.datamodel.DataModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 数据模型变更审批 apply: 复用 DataModelService.review。 */
@Component
@RequiredArgsConstructor
public class DataModelApprovalHandler implements ApprovalApplyHandler {
    public static final String FLOW = "DATAMODEL_CHANGE";
    private final DataModelService dataModelService;

    @Override
    public String flowType() { return FLOW; }

    @Override
    public void onApproved(DnApproval a) {
        dataModelService.review(Long.valueOf(a.getBizId()), "approved", a.getReviewComment());
    }

    @Override
    public void onRejected(DnApproval a) {
        String c = a.getReviewComment() == null || a.getReviewComment().trim().isEmpty() ? "驳回" : a.getReviewComment();
        dataModelService.review(Long.valueOf(a.getBizId()), "rejected", c);
    }
}
