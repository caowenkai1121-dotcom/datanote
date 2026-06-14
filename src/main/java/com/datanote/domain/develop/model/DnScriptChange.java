package com.datanote.domain.develop.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚本上线变更工单(申请/审批流转) — dn_script_change 表。
 * 数据开发脚本上线由直接上线改为审批制: 提交→审批→上线, 对齐数据模型/主数据的治理范式。
 */
@Data
@TableName("dn_script_change")
public class DnScriptChange {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scriptId;
    private String changeType;        // ONLINE/OFFLINE
    private String payloadJson;       // 提交时脚本内容快照(审批所见即所发)
    private String reason;            // 申请说明
    private String status;            // pending/approved/rejected
    private String requestedBy;
    private String reviewer;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    @TableField(exist = false)
    private String scriptName;
}
