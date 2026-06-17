package com.datanote.platform.ai.agent.tool;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工具执行上下文：当前用户/IP/角色/会话id + 业务情境(bizCtx) + 发起人权限快照(perms/roles)。
 *  权限快照在边界一次性解析后随上下文全程携带(含子代理/cron/resume), 不读 SecurityContextHolder, 修复异步 ThreadLocal 失效。 */
@Data
public class AgentContext {
    private String userName;
    private String ip;
    private String role;
    private String sessionId;
    /** 业务情境：route/db/table/jobId/ruleId... 由各模块情境入口透传，工具缺参时可回退取用。 */
    private Map<String, Object> bizCtx;

    /** 发起人有效权限点集(角色∪直授, 含 '*'); null=未解析。 */
    private Set<String> perms;
    /** 发起人角色编码集(供数据级 ROLE 授权判定)。 */
    private List<String> roles;
    /** 是否已解析权限快照(避免重复查库)。 */
    private boolean permsResolved;

    /** 保留原 5 参构造, 既有 8 处调用点不变; perms/roles 由 AgentPermResolver 经 setter 填充。 */
    public AgentContext(String userName, String ip, String role, String sessionId, Map<String, Object> bizCtx) {
        this.userName = userName;
        this.ip = ip;
        this.role = role;
        this.sessionId = sessionId;
        this.bizCtx = bizCtx;
    }
}
