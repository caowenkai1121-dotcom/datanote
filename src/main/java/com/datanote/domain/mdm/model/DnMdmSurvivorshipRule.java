package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 存活性规则 — 对应 dn_mdm_survivorship_rule 表
 * 定义某实体每个属性在黄金记录合并时的存活策略（自动选最佳值）。
 */
@Data
@TableName("dn_mdm_survivorship_rule")
public class DnMdmSurvivorshipRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;
    private String attrCode;
    private String attrName;
    private String strategy;          // latest最新 / most_complete最完整 / source_priority源优先
    private String sourcePriority;    // 源系统优先级清单（逗号分隔，仅 source_priority 策略时用），可空
    private Integer priority;          // 规则优先级（数字越小越优先）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
