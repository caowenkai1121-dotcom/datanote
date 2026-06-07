package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 参考数据/码表实体 — 对应 dn_mdm_reference 表
 * 系统级枚举与码表（国家/地区/行业分类等），支持 parent_code 树形结构。
 */
@Data
@TableName("dn_mdm_reference")
public class DnMdmReference {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String category;     // 码表类别，如 CUSTOMER_TYPE / REGION
    private String code;         // 码值
    private String name;         // 码值名称
    private String parentCode;   // 父级码值（树形，可空）
    private Integer sortOrder;   // 排序
    private Integer status;      // 1启用 0停用
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
