package com.datanote.platform.notify;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 站内通知（dn_notification）。IV-1 第二步: 协作触达。 */
@Data
@TableName("dn_notification")
public class DnNotification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String receiver;
    private String type;
    private String title;
    private String refRoute;
    private Long refId;
    private String refTab;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
