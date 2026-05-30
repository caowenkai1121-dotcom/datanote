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

    // 迭代V3 新增
    private Long folderId;              // 所属文件夹，0=根
    private String deleteMode;          // PHYSICAL/LOGICAL（CDC 源端删除策略）
    private String logicalDeleteField;  // 逻辑删除标记列
    private String logicalDeleteValue;  // 逻辑删除写入值，默认 '1'
    private Integer markSyncTs;         // 是否标记同步时间戳（1=是）
    private String syncTsField;         // 同步时间戳列名

    // M1 数据加工管道
    private String preSql;   // 任务级前置SQL
    private String postSql;  // 任务级后置SQL

    // M2a 健壮性
    private Integer errorLimitRows;
    private java.math.BigDecimal errorLimitRatio;
    private String retryBackoffType;
    private Integer retryBackoffDelay;
    private String rateLimitMode;
    private Integer rateLimitValue;

    // M4b CDC 深水
    private Integer incrementalSnapshotEnabled;
    private Integer ddlSyncEnabled;
}
