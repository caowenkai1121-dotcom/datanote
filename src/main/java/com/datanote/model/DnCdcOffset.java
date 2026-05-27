package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CDC binlog 位点（Debezium offset 持久化，断点续传）— 对应 dn_cdc_offset 表。
 * 按 job_id 隔离，每个 (job_id, offset_key) 唯一。
 */
@Data
@TableName("dn_cdc_offset")
public class DnCdcOffset {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 dn_sync_job.id */
    private Long jobId;

    /** Debezium offset key（partition 的序列化串） */
    private String offsetKey;

    /** Debezium offset value（binlog file+pos/gtid 的序列化串） */
    private String offsetValue;

    private LocalDateTime updatedAt;
}
