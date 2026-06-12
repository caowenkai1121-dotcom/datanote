package com.datanote.domain.datamodel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型变更工单(申请/审批流转) — dn_model_change 表。
 */
@Data
@TableName("dn_model_change")
public class DnModelChange {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private String changeType;        // CREATE/UPDATE/PUBLISH/ARCHIVE
    private String payloadJson;
    private String reason;
    private String status;            // pending/approved/rejected
    private String requestedBy;
    private String reviewer;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    @TableField(exist = false)
    private String modelName;
    @TableField(exist = false)
    private String modelCode;
}
