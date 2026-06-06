package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据变更审批请求 — 对应 dn_mdm_change_request 表。
 * 对黄金记录的变更（新增/修改/删除）走审批流，变更内容以 JSON 柔性存储。
 */
@Data
@TableName("dn_mdm_change_request")
public class DnMdmChangeRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;            // 所属实体
    private Long goldenRecordId;      // 关联黄金记录（create 时可空）
    private String changeType;        // create/update/delete
    private String bizKey;            // 业务主键（展示用）
    private String payloadJson;       // 变更内容 JSON {attrCode: value}
    private String reason;            // 变更原因
    private String status;            // pending/approved/rejected
    private String requestedBy;       // 申请人
    private String reviewer;          // 审批人
    private String reviewComment;     // 审批意见
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 非持久化：展示用实体名称
    @TableField(exist = false)
    private String entityName;
}
