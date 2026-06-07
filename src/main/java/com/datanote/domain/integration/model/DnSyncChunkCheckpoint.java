package com.datanote.domain.integration.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全量分片断点续传 — 对应 dn_sync_chunk_checkpoint 表。
 */
@Data
@TableName("dn_sync_chunk_checkpoint")
public class DnSyncChunkCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long syncJobId;

    private String sourceTable;

    /** JSON 数组：复合主键游标值（字符串） */
    private String cursorValue;

    private Long rowCount;

    private LocalDateTime updatedAt;
}
