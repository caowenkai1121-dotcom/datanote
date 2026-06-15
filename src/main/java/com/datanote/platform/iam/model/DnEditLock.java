package com.datanote.platform.iam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 资源独占编辑锁 — dn_edit_lock。心跳超时自动失效。 */
@Data
@TableName("dn_edit_lock")
public class DnEditLock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceType;
    private String resourceId;
    private String holder;
    private LocalDateTime acquiredAt;
    private LocalDateTime heartbeatAt;
}
