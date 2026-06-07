package com.datanote.domain.integration.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** DS-M7：源表 schema 快照——对应 dn_sync_schema_snapshot。 */
@Data
@TableName("dn_sync_schema_snapshot")
public class DnSyncSchemaSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private String sourceTable;
    private String columnsJson;
    private String pkJson;
    private LocalDateTime updatedAt;
}
