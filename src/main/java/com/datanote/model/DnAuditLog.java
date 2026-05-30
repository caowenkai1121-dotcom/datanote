package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局审计日志 — 对应 dn_audit_log 表（只增不改）。
 * 覆盖登录/数据访问/导出/权限变更/元数据与规则变更/打标降级等动作类型。
 */
@Data
@TableName("dn_audit_log")
public class DnAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人；匿名记 'anonymous' */
    private String userName;

    /** LOGIN/DATA_ACCESS/EXPORT/PERM_CHANGE/META_CHANGE/RULE_CHANGE/LABEL_CHANGE/OTHER */
    private String actionType;

    /** 操作对象（预留，可空） */
    private String target;

    /** HTTP 方法 */
    private String method;

    /** 请求路径 */
    private String path;

    /** 客户端 IP */
    private String ip;

    /** 响应状态码 */
    private Integer status;

    /** 详情（耗时/查询串等） */
    private String detail;

    private LocalDateTime createdAt;
}
