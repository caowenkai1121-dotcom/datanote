package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打标 / 降级审批留痕 — 对应 dn_label_audit
 */
@Data
@TableName("dn_label_audit")
public class DnLabelAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tableMetaId;
    private String columnName;
    private String oldLevel;
    private String newLevel;
    private String sensitiveType;
    private String operator;
    private String reason;
    private LocalDateTime createdAt;
}
