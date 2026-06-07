package com.datanote.domain.governance.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DCMM 八大域成熟度自评 — 对应 dn_maturity_assessment
 */
@Data
@TableName("dn_maturity_assessment")
public class DnMaturityAssessment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String domain;       // DCMM 八大域
    private BigDecimal score;    // 0-100
    private Integer level;       // 1-5
    private String note;
    private LocalDateTime assessedAt;
}
