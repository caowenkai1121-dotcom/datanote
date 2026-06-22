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
    /** 无人值守自主执行: 1=后台驱动器持续推进至计划完成/预算耗尽(自动批非HIGH写, HIGH写仍挂起等人) */
    private Integer autonomous;
    /** 自主任务全程生产步硬上限(0=不限) */
    private Integer autoMaxSteps;
    /** 自主任务墙钟截止(超时自动收尾) */
    private LocalDateTime autonomousUntil;
    /** 自主驱动心跳(检测卡死/防多实例重复领取) */
    private LocalDateTime lastHeartbeat;
    /** 自主连续无进展周期数(熔断用) */
    private Integer autoIdleCount;
    private Integer budgetStepsUsed;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
