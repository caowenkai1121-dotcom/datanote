package com.datanote.domain.datamodel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 实体属性(L5 字段, 可绑数据标准/码表) — dn_model_attribute 表。
 */
@Data
@TableName("dn_model_attribute")
public class DnModelAttribute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;
    private String attrCode;
    private String attrName;
    private String dataType;
    private String dataLength;
    private Integer isPk;
    private Integer isNullable;
    private String defaultValue;
    private String elementCode;       // 绑数据标准 dn_data_element
    private String dictCode;          // 绑码表 dn_code_dict
    private Long refEntityId;         // 外键引用实体
    private String physicalColumn;    // 物理模型映射列名
    private String bizDefinition;
    private Integer sortOrder;
}
