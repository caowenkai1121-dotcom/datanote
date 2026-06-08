package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.model.DnAiSession;

/** Agent 会话运行态（内存）：当前会话 + 步序游标 + 轨迹文本 + 终态标志。 */
public class AgentState {
    public DnAiSession session;
    /** 下一步序号（持续递增，跨多轮） */
    public int seq;
    /** 注入下一轮 prompt 的已执行步骤与工具结果摘要 */
    public final StringBuilder trace = new StringBuilder();
    public String finalAnswer;
    public boolean done;
    public boolean blocked;
    public String blockReason;
    /** 写动作待审批挂起(会话置 wait_approval, 循环跳出, 审批后 resume 重入) */
    public boolean awaitingApproval;
    public String pendingSkill;
}

