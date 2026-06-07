package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据域实体 — 对应 dn_mdm_domain 表（MDM 域：客户/产品/供应商/组织等）
 */
@Data
@TableName("dn_mdm_domain")
public class DnMdmDomain {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String domainCode;
    private String domainName;
    private String category;     // 客户/产品/供应商/组织/财务/其他
    private String owner;
    private String description;
    private Integer status;      // 1启用 0停用
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 非持久化：展示用统计（实体数）
    @TableField(exist = false)
    private Integer entityCount;
}
