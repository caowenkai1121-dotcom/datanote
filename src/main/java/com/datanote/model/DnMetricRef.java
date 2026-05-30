package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标-资产关联实体 — 对应 dn_metric_ref 表
 */
@Data
@TableName("dn_metric_ref")
public class DnMetricRef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private String dbName;
    private String tableName;
    private String columnName;
    private String refType;
    private LocalDateTime createdAt;
}
