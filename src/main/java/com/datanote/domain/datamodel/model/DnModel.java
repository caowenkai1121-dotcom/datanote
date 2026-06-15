package com.datanote.domain.datamodel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据模型(三层: BIZ业务/LOGIC逻辑/PHYS物理) — dn_model 表。
 */
@Data
@TableName("dn_model")
public class DnModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelCode;
    private String modelName;
    private String modelType;        // BIZ/LOGIC/PHYS
    private Long subjectId;          // 所属主题域
    private Long sourceModelId;      // 溯源(逻辑←业务, 物理←逻辑)
    private String dwLayer;          // 数仓分层 ODS/DWD/DIM/DWS/ADS
    private Integer version;
    private String status;           // DRAFT/PENDING/PUBLISHED/REJECTED/ARCHIVED
    private String owner;
    private String description;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 并发编辑乐观校验基线(非库字段) */
    @TableField(exist = false)
    private LocalDateTime baseUpdatedAt;

    @TableField(exist = false)
    private String subjectName;
    @TableField(exist = false)
    private String sourceModelName;
    @TableField(exist = false)
    private Integer entityCount;
    @TableField(exist = false)
    private java.util.List<DnModelEntity> entities;
    @TableField(exist = false)
    private java.util.List<DnModelRelation> relations;
}
