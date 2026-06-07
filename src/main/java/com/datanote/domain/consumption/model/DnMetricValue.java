package com.datanote.domain.consumption.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 指标值时序快照 — 对应 dn_metric_value。指标执行引擎按 calc_formula 计算后落库，供消费层查询/看板/导出。
 */
@Data
@TableName("dn_metric_value")
public class DnMetricValue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metricId;
    private String metricCode;
    private BigDecimal metricValue;
    private String valueText;
    private LocalDate bizDate;
    private String dims;
    private String runStatus;
    private String errorMsg;
    private Long durationMs;
    private String calcSql;
    private String createdBy;
    private LocalDateTime createdAt;
}
