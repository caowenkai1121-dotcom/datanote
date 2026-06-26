package com.datanote.domain.approval;

import com.datanote.domain.approval.model.DnApproval;

/** 流相关 apply 处理器: 各业务流(主数据/数据模型/脚本)实现并注册, 审批通过/驳回时由审批事件消费者回调。实现须幂等(至少一次投递)。 */
public interface ApprovalApplyHandler {
    /** 负责的流类型, 与 DnApproval.flowType 对应 */
    String flowType();

    /** 审批通过: 落地下游(如脚本上线/黄金记录生效/模型变更应用)。幂等。 */
    void onApproved(DnApproval approval);

    /** 审批驳回: 可选清理(如标记底层变更为已驳回)。幂等。 */
    default void onRejected(DnApproval approval) {}
}
