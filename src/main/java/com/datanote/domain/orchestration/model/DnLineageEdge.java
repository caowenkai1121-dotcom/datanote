package com.datanote.domain.orchestration.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据血缘边 — 对应 dn_lineage_edge
 */
@Data
@TableName("dn_lineage_edge")
public class DnLineageEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String levelType;     // TABLE / COLUMN
    private String srcDb;
    private String srcTable;
    private String srcColumn;
    private String dstDb;
    private String dstTable;
    private String dstColumn;
    private String transformType; // DIRECT / TRANSFORM / MASK
    private String source;        // MAPPING / SQL / SCHEDULE / MANUAL
    private Integer confidence;
    private Long jobId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
