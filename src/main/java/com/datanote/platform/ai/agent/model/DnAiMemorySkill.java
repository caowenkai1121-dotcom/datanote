package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI Agent 自学习记忆/技能实体 — 对应 dn_ai_memory_skill 表。
 * 每会话结束后异步沉淀一条可复用经验, 下次同类任务前语义召回注入 prompt(只读上下文, 绝不影响护栏/审批)。
 */
@Data
@TableName("dn_ai_memory_skill")
public class DnAiMemorySkill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;          // memory / skill
    private String title;         // 标题
    private String content;       // 经验事实/操作技能
    private String triggerHint;   // 触发场景(什么时候用得上)
    private String owner;         // 归属用户
    private String status;        // active / archived
    private Integer hitCount;     // 召回命中次数
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
