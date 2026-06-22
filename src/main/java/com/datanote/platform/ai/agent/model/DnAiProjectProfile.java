package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 项目画像(全局长久记忆) — dn_ai_project_profile。profile_key='global' 为项目画像; '__digest_date__' 为每日汇总占位。 */
@Data
@TableName("dn_ai_project_profile")
public class DnAiProjectProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String profileKey;
    private String content;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
