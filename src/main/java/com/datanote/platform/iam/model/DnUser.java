package com.datanote.platform.iam.model;

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
    /** 最后登录时间(每次登录成功更新) */
    private LocalDateTime lastLoginAt;
    /** 首登强制改密标志: 1需改/0正常(管理员建号或重置密码后置1, 用户自助改密后清0) */
    private Integer mustChangePwd;
    /** 邮箱 */
    private String email;
    /** 手机号 */
    private String phone;
    /** 部门 */
    private String department;
    /** 岗位/职位 */
    private String position;
    /** 员工工号 */
    private String employeeId;
    /** 备注 */
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
