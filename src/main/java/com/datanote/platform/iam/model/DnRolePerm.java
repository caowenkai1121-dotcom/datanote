package com.datanote.platform.iam.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * RBAC 角色-权限关联实体 — 对应 dn_role_perm 表
 */
@Data
@TableName("dn_role_perm")
public class DnRolePerm {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roleId;
    /** 权限点，'*' 表示全权限 */
    private String permCode;
}
