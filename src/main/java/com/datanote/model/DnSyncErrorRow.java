package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步坏行（DLQ）— 对应 dn_sync_error_row。全量/增量逐行写失败的行落此表，可见可重试。
 */
@Data
@TableName("dn_sync_error_row")
public class DnSyncErrorRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Long runId;
    private String sourceTable;
    private String rawRow;
    private String errorCode;
    private String errorMsg;
    private String stage;
    private Integer attempt;
    private LocalDateTime createdAt;
}
