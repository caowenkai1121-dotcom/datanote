package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 治理项规则库（五维打分项，权重可配） — 对应 dn_governance_metric
 */
@Data
@TableName("dn_governance_metric")
public class DnGovernanceMetric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dimension;    // 规范/质量/安全/生命周期/血缘
    private String metricCode;
    private String metricName;
    private BigDecimal weight;
    private Integer enabled;
}
