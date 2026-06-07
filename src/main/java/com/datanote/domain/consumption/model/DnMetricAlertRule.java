package com.datanote.domain.consumption.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标预警阈值规则 — 对应 dn_metric_alert_rule。指标计算后按规则判定越界 → 自动生成治理工单。
 */
@Data
@TableName("dn_metric_alert_rule")
public class DnMetricAlertRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private String metricCode;
    private String op;             // GT/LT/GE/LE/NE/OUT/IN
    private BigDecimal thresholdMin;
    private BigDecimal thresholdMax;
    private String severity;
    private Integer enabled;
    private String remark;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
