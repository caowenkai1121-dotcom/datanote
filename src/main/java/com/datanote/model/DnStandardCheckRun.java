package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 落标稽核结果实体 — 对应 dn_standard_check_run
 */
@Data
@TableName("dn_standard_check_run")
public class DnStandardCheckRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scope;
    private Integer totalCount;
    private Integer violationCount;
    private BigDecimal passRate;
    private String detail;
    private LocalDateTime createdAt;
}
