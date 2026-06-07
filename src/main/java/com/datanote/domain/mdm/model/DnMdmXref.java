package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主数据交叉引用(XREF) — 对应 dn_mdm_xref 表。
 * 维护黄金记录(MDM ID)与各源系统业务ID的映射，支持按源ID反查黄金记录。
 */
@Data
@TableName("dn_mdm_xref")
public class DnMdmXref {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long goldenRecordId;   // 关联的黄金记录(MDM 全局ID)
    private Long entityId;         // 冗余,便于按实体查询
    private String sourceSystem;   // 源系统(CRM/ERP/...)
    private String sourceId;       // 源系统业务ID
    private BigDecimal matchScore; // 匹配置信度(可选)
    private Integer isPrimary;     // 是否主源 1/0
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 非持久化：展示用（黄金记录业务主键）
    @TableField(exist = false)
    private String bizKey;
}
