package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据采集日志 — 对应 dn_meta_collect_log
 */
@Data
@TableName("dn_meta_collect_log")
public class DnMetaCollectLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private String dbType;
    private String scope;
    private Integer tableCount;
    private Integer columnCount;
    private String status;
    private String message;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
