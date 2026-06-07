package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 治理工单单一事实表 — 对应 dn_governance_issue
 */
@Data
@TableName("dn_governance_issue")
public class DnGovernanceIssue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String issueType;    // STANDARD/QUALITY/SECURITY/LINEAGE/LIFECYCLE/OTHER
    private String dimension;
    private String objectRef;
    private String title;
    private String description;
    private String severity;     // HIGH/MEDIUM/LOW
    private String owner;
    private String status;       // OPEN/FIXING/RESOLVED/VERIFIED/CLOSED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 运营态计算字段(非持久列, R4) =====
    @TableField(exist = false)
    private Long ageHours;       // 自创建至今存活小时
    @TableField(exist = false)
    private Boolean overdue;     // 是否超 SLA(仅 OPEN/FIXING)
}
