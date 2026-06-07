package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据黄金记录 — 对应 dn_mdm_golden_record 表。
 * 属性值以 JSON 柔性存储（key=属性编码），适配各实体的动态 schema。
 */
@Data
@TableName("dn_mdm_golden_record")
public class DnMdmGoldenRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long entityId;
    private String bizKey;        // 业务主键值（展示/去重用，取关键属性值）
    private String dataJson;      // {attrCode: value} 全属性值 JSON
    private String status;        // draft/active/inactive
    private Integer version;
    private String sourceSystem;  // 来源系统
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
