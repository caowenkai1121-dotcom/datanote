package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI Agent 步骤（dn_ai_step）。消息流 + 工具调用日志 + 计划记录 三合一，逐道工序详记可回放。 */
@Data
@TableName("dn_ai_step")
public class DnAiStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Integer seq;
    /** PLAN/SKILL_CALL/REPLAN/FINAL */
    private String stepType;
    /** user/assistant/tool */
    private String role;
    private String content;
    private String thinkContent;
    private String skillName;
    private String skillGroup;
    private String argsJson;
    private String resultStatus;
    private String resultType;
    private String resultData;
    private Integer readOnly;
    private String riskLevel;
    private Long latencyMs;
    private LocalDateTime createdAt;
}
