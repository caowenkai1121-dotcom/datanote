package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 主数据发布日志 — 对应 dn_mdm_publish_log 表。
 * 记录每次向订阅方发布黄金记录变更的结果。
 */
@Data
@TableName("dn_mdm_publish_log")
public class DnMdmPublishLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subscriptionId;      // 关联订阅
    private Long goldenRecordId;      // 黄金记录
    private String changeType;        // create/update/delete
    private String bizKey;            // 业务主键(展示用)
    private String status;            // success/failed
    private String message;           // 推送结果说明
    private LocalDateTime publishedAt;

    // 非持久化：展示用订阅方系统
    @TableField(exist = false)
    private String subscriberSystem;
}
