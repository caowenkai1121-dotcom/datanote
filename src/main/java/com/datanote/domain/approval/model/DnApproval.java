package com.datanote.domain.approval.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 统一审批记录: 各业务流提交即建一条, 统一状态机。 */
@Data
@TableName("dn_approval")
public class DnApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String flowType;       // MDM_CHANGE / DATAMODEL_CHANGE / SCRIPT_CHANGE
    private String bizId;          // 底层业务记录 id
    private String title;
    private String submitter;
    private String status;         // PENDING / APPROVED / REJECTED
    private String reviewer;       // 允许 == submitter(自审自批)
    private String reviewComment;
    private String payloadJson;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
}
