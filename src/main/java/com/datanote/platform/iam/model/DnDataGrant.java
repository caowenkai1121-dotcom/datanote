package com.datanote.platform.iam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据权限授权 — 对应 dn_data_grant 表。
 * 某资源(resource_type+resource_id)有 >=1 行即"受限", 仅被授权主体(角色/用户)+owner+超管+data:all 可访问;
 * 无任何行 = 公开(默认)。
 */
@Data
@TableName("dn_data_grant")
public class DnDataGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceType;   // TABLE/PROJECT/MODEL/METRIC/SCRIPT
    private String resourceId;     // 库表=db.table, 其余=id
    private String principalType;  // ROLE/USER
    private String principal;      // 角色编码 或 用户名
    private String createdBy;
    private LocalDateTime createdAt;
}
