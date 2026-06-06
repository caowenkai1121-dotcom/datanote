package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据发布订阅 — 对应 dn_mdm_subscription 表。
 * 订阅方系统订阅某实体的黄金记录变更，变更发生时向 endpoint 推送。
 */
@Data
@TableName("dn_mdm_subscription")
public class DnMdmSubscription {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String subscriberSystem;  // 订阅方系统
    private Long entityId;             // 订阅的实体
    private String changeTypes;        // 订阅的变更类型(逗号分隔: create,update,delete)
    private String endpoint;           // 推送地址
    private Integer status;            // 1启用 0停用
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 非持久化：展示用实体名称
    @TableField(exist = false)
    private String entityName;
}
