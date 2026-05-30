package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RBAC 用户实体 — 对应 dn_user 表
 */
@Data
@TableName("dn_user")
public class DnUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 哈希密码 */
    private String password;
    private String nickname;
    /** 状态：1启用/0停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
