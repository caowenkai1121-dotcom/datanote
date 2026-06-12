package com.datanote.domain.datamodel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实体关系 — dn_model_relation 表。
 */
@Data
@TableName("dn_model_relation")
public class DnModelRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private Long sourceEntityId;
    private Long targetEntityId;
    private String relationType;      // 1:1 / 1:N / M:N
    private Long fkAttrId;
    private String description;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private String sourceEntityName;
    @TableField(exist = false)
    private String targetEntityName;
}
