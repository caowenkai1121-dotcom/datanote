package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 关系库同步任务实体 — 对应 dn_sync_job 表
 */
@Data
@TableName("dn_sync_job")
public class DnSyncJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobName;
    private Long sourceDsId;
    private Long targetDsId;
    private String sourceDb;
    private String targetDb;
    private String syncMode;        // FULL/INCREMENTAL/CDC
    private String tableConfig;     // JSON
    private String fieldMapping;    // JSON
    private String writeMode;       // UPSERT/INSERT/INSERT_IGNORE
    private Integer batchSize;
    private String scheduleCron;
    private String scheduleStatus;
    private String status;          // CREATED/RUNNING/STOPPED/PAUSED/FAILED
    private Integer retryTimes;
    private Integer timeoutSeconds;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
