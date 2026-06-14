package com.datanote.platform.iam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户直授权限点 — 对应 dn_user_perm 表。
 * 有效权限 = 角色权限并集 ∪ 用户直授(叠加), 用于绕过角色给个别用户临时加权限。
 */
@Data
@TableName("dn_user_perm")
public class DnUserPerm {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String permCode;
    private String createdBy;
    private LocalDateTime createdAt;
}
