package com.datanote.domain.datamodel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型实体(L3业务对象/L4逻辑实体) — dn_model_entity 表。
 */
@Data
@TableName("dn_model_entity")
public class DnModelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private String entityCode;
    private String entityName;
    private Integer level;            // 3业务对象/4逻辑实体
    private Long parentEntityId;
    private Long sourceEntityId;      // 逻辑实体←业务对象
    private String physicalTable;     // 物理模型映射表名
    private String bizDefinition;
    private Integer sortOrder;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private List<DnModelAttribute> attributes;
}
