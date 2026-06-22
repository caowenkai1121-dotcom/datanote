package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 用户画像(用户隔离的长久记忆) — dn_ai_user_profile。每日由该用户沉淀经验蒸馏, 注入 prompt 让 agent 越来越懂用户。 */
@Data
@TableName("dn_ai_user_profile")
public class DnAiUserProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userName;   // 隔离键
    private String content;    // 蒸馏后的画像正文
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
