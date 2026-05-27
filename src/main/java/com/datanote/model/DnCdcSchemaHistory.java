package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC 表结构变更历史（Debezium schema history，解析 binlog DDL 必需）— 对应 dn_cdc_schema_history 表。
 * 按 job_id 隔离，每条记录是一个 Debezium HistoryRecord 的 JSON 序列化。
 */
@Data
@TableName("dn_cdc_schema_history")
public class DnCdcSchemaHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 dn_sync_job.id */
    private Long jobId;

    /** Debezium schema history 一条记录（JSON 字符串） */
    private String historyData;

    private LocalDateTime createdAt;
}
