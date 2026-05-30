package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 行级权限策略实体 — 对应 dn_row_policy 表（M9）。
 */
@Data
@TableName("dn_row_policy")
public class DnRowPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String roleCode;
    private String dbName;
    private String tableName;
    /** 行过滤 WHERE 片段 */
    private String rowFilter;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
