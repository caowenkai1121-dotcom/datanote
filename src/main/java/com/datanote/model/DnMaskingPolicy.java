package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 脱敏策略实体 — 对应 dn_masking_policy 表（M9）。
 */
@Data
@TableName("dn_masking_policy")
public class DnMaskingPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String policyName;
    /** SENSITIVE_TYPE / COLUMN */
    private String matchDim;
    private String sensitiveType;
    private String dbName;
    private String tableName;
    private String columnName;
    /** MASK/HASH/REPLACE/RANGE */
    private String maskingFunc;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
