package com.datanote.domain.governance.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生命周期策略 — 对应 dn_lifecycle_policy
 */
@Data
@TableName("dn_lifecycle_policy")
public class DnLifecyclePolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dbName;
    private String tableName;
    private String policyType;   // HOT_COLD / TTL / ARCHIVE
    private Integer coldDays;
    private Integer ttlDays;
    private Integer enabled;
    private String status;       // NEW/ACTIVE/PENDING/FAILED/DROP_PENDING/DROPPED
    private String ddlText;
    private String lastMsg;
    private LocalDateTime dropDueAt;
    private String approver;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
