package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.iam.RbacService;

/**
 * 功能级权限闸门(纯静态可单测, 与 Guardrail 并列)。
 * 只认 tool.requiredPerm() 与 ctx.perms(发起人权限快照), 不读 SecurityContextHolder。
 * null perm = 仅需登录(读类默认); 有 perm 需精确命中或 '*'; perms 未解析且需权限 → fail-closed DENY。
 */
public final class PermGate {

    private PermGate() {}

    public enum Decision { ALLOW, DENY }

    public static Decision check(AiTool tool, AgentContext ctx) {
        if (tool == null) return Decision.DENY;
        String need = tool.requiredPerm();
        if (need == null || need.isEmpty()) {
            if (tool.readOnly()) return Decision.ALLOW;          // 只读未声明权限点: 仅需登录放行
            // 写工具(有副作用)未声明 requiredPerm = 漏标, fail-closed 视为高危: 仅超管('*')可执行(spec §十)
            return (ctx != null && ctx.getPerms() != null && ctx.getPerms().contains("*"))
                    ? Decision.ALLOW : Decision.DENY;
        }
        if (ctx == null || ctx.getPerms() == null) return Decision.DENY; // fail-closed: 未解析却要权限
        return RbacService.hasPermission(ctx.getPerms(), need) ? Decision.ALLOW : Decision.DENY;
    }
}
