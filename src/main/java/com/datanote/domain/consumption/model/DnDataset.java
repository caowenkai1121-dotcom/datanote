package com.datanote.domain.consumption.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据集/数据产品 — 对应 dn_dataset。一段精选 SQL 注册为可复用、受治理(脱敏+审计)的查询。
 */
@Data
@TableName("dn_dataset")
public class DnDataset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String datasetCode;
    private String datasetName;
    private String description;
    private String defaultDb;
    private String querySql;
    private String owner;
    private Integer status;
    private String tags;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
