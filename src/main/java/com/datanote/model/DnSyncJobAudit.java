package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步任务操作审计 — 对应 dn_sync_job_audit 表。
 */
@Data
@TableName("dn_sync_job_audit")
public class DnSyncJobAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String jobName;

    /** CREATE/UPDATE/RUN/STOP/RESET/DELETE */
    private String operationType;

    private String operator;

    /** 变更/操作详情 */
    private String changeDetail;

    private LocalDateTime createdAt;
}
