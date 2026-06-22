package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI Agent 会话主表（dn_ai_session）。承载多轮历史/状态机/中断转向标志/规划快照/预算计数。 */
@Data
@TableName("dn_ai_session")
public class DnAiSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String userName;
    private String goalIntent;
    /** running/paused/wait_approval/done/blocked/cancelled */
    private String status;
    private Integer interruptFlag;
    private String steerText;
    private String planJson;
    /** 本任务批量自动批准写操作: 1=后续写操作免逐个审批(仍受 PermGate/DataAcl 拦截); 任务 done 时清 0 */
    private Integer autoApprove;
    private Integer budgetStepsUsed;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
