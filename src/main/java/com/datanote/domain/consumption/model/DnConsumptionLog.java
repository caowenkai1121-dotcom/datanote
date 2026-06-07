package com.datanote.domain.consumption.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据消费审计流水 — 对应 dn_consumption_log。透明记录 谁/何时/消费什么/行数/耗时/成败。
 */
@Data
@TableName("dn_consumption_log")
public class DnConsumptionLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String consumer;
    private String targetType;
    private String targetCode;
    private String action;
    private Long rowCount;
    private Long durationMs;
    private Integer success;
    private String detail;
    private LocalDateTime createdAt;
}
