package com.datanote.domain.integration.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC 死信（坏事件）— 对应 dn_cdc_dead_letter 表。
 * 记录解析失败（PARSE）或应用失败（APPLY）的变更事件，供人工排查。
 */
@Data
@TableName("dn_cdc_dead_letter")
public class DnCdcDeadLetter {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 dn_sync_job.id */
    private Long jobId;

    /** 源库名 */
    private String sourceDb;

    /** 源表名 */
    private String sourceTable;

    /** JSON 原始变更（截断） */
    private String originValue;

    /** 失败原因 */
    private String errorReason;

    /** 失败类型：PARSE / APPLY */
    private String errorType;

    private LocalDateTime createdAt;
}
