package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.model.DnAiSession;

/** Agent 会话运行态（内存）：当前会话 + 步序游标 + 轨迹文本 + 终态标志。 */
public class AgentState {
    public DnAiSession session;
    /** 下一步序号（持续递增，跨多轮） */
    public int seq;
    /** 注入下一轮 prompt 的已执行步骤与工具结果摘要(可被上下文压缩替换, 故非 final) */
    public StringBuilder trace = new StringBuilder();
    /** 本轮上下文压缩次数 */
    public int compressionCount;
    /** 上次压缩后的 trace 长度(防抖动: 增长不足则跳过再压) */
    public int lastCompressedLen;
    public String finalAnswer;
    public boolean done;
    public boolean blocked;
    public String blockReason;
    /** 写动作待审批挂起(会话置 wait_approval, 循环跳出, 审批后 resume 重入) */
    public boolean awaitingApproval;
    public String pendingSkill;
    /** 等待用户输入挂起(ask_user: 决策/协助卡片, 会话置 wait_input, 用户答后 /answer 续跑) */
    public boolean awaitingInput;
    /** 待用户回答的问题清单(JSON, 透传前端渲染卡片) */
    public com.fasterxml.jackson.databind.JsonNode pendingQuestions;
    /** 结构化退出原因(透明可复核): DONE/BUDGET_EXHAUSTED/HARD_CAP_EXHAUSTED/WALLCLOCK_EXHAUSTED/INTERRUPTED/BLOCKED/AWAIT_APPROVAL/AWAIT_INPUT */
    public String exitReason;
    /** 本轮是否有写工具执行失败(防 AI 谎报成功, 终答追加如实 footer) */
    public boolean hadWriteFailure;
    public String writeFailureNote;
}

