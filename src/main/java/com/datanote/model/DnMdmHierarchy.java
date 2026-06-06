package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 主数据层级关系 — 对应 dn_mdm_hierarchy 表。
 * 维护黄金记录间的树形层级(组织架构/地区/产品分类),父子均指向黄金记录。
 */
@Data
@TableName("dn_mdm_hierarchy")
public class DnMdmHierarchy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;          // 所属实体
    private String hierarchyType;   // 层级类型(org/region/category...)
    private Long parentRecordId;    // 父黄金记录(根节点可空)
    private Long childRecordId;     // 子黄金记录
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 非持久化：展示用（父/子黄金记录业务主键）
    @TableField(exist = false)
    private String parentBizKey;
    @TableField(exist = false)
    private String childBizKey;

    // 非持久化：tree 接口构建用（子节点的业务主键 + 下挂子树）
    @TableField(exist = false)
    private String bizKey;
    @TableField(exist = false)
    private List<DnMdmHierarchy> children;
}
