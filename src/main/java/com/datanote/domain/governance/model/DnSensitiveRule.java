package com.datanote.domain.governance.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感识别规则 — 对应 dn_sensitive_rule
 */
@Data
@TableName("dn_sensitive_rule")
public class DnSensitiveRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleName;
    private String matchType;     // COLUMN_NAME / REGEX / VALIDATOR
    private String pattern;       // 关键词(逗号分隔) / 正则 / 校验器名
    private String sensitiveType; // PHONE / EMAIL / ID_CARD / BANK_CARD / USCC ...
    private String suggestLevel;  // 建议密级(level_name)
    private Integer enabled;      // 1 启用 0 停用
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
