package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据实体 — 对应 dn_mdm_entity 表（某个域下的实体定义，如 customer_golden）
 */
@Data
@TableName("dn_mdm_entity")
public class DnMdmEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long domainId;
    private String entityCode;
    private String entityName;
    private String description;
    private Integer status;      // 1启用 0停用
    private Integer attrCount;   // 冗余属性数（展示用）
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 非持久化：所属域名称（联表展示用）
    @TableField(exist = false)
    private String domainName;
}
