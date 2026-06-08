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
    private String riskLevel;
    /** pending / approved / rejected */
    private String status;
    private String decidedBy;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
