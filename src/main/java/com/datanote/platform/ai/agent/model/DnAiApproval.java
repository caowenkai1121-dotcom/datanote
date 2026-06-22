package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI Agent 写动作审批留痕（dn_ai_approval）。幂等键 session_id+step_seq+skill_name。 */
@Data
@TableName("dn_ai_approval")
public class DnAiApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Integer stepSeq;
    private String skillName;
    private String argsJson;
    /** 人类可读操作摘要(审批卡展示, 替代 skillName+argsJson 原文) */
    private String actionSummary;
    private String riskLevel;
    /** pending / approved / rejected */
    private String status;
    private String decidedBy;
    private LocalDateTime decidedAt;
    /** 已批写操作的实际执行时间(NULL=已批未执行); resume 重放后置位, 防重复执行 */
    private LocalDateTime executedAt;
    private LocalDateTime createdAt;
}
