package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据实体属性 — 对应 dn_mdm_attribute 表（实体的字段定义，含数据类型/必填/关键字段等）
 */
@Data
@TableName("dn_mdm_attribute")
public class DnMdmAttribute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;
    private String attrCode;
    private String attrName;
    private String dataType;     // STRING/INT/DECIMAL/DATE/BOOLEAN/ENUM/REFERENCE
    private Integer lengthLimit;
    private Integer required;     // 是否必填 1/0
    private Integer isKey;        // 是否关键字段（用于匹配去重） 1/0
    private Integer isUnique;     // 是否唯一 1/0
    private String enumValues;    // ENUM 类型的候选值（逗号分隔）
    private String defaultValue;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
