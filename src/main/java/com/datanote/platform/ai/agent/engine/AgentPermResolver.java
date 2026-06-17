package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.config.AuthProperties;
import com.datanote.platform.iam.RbacService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 把用户名解析为权限快照(perms/roles)填入 AgentContext。
 *  开放模式(未设密码)→ '*'(全放行); 启用模式查 RBAC; 查询异常 fail-closed 给空集。
 *  由边界入口(Controller/Cron/resume)调用填充, 消费方只读, 不在工具层自行查库。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPermResolver {

    private final RbacService rbacService;
    private final AuthProperties authProperties;

    public void resolveInto(AgentContext ctx, String caller) {
        if (ctx == null) return; // null ctx 属调用方 bug, 不标记已解析(调用链 Controller/Cron/resume 不会传 null)
        if (ctx.isPermsResolved()) return; // 幂等: 已解析过的快照不重复查库/覆盖(同一 ctx 只解析一次)
        Set<String> perms;
        List<String> roles;
        if (!authProperties.isEnabled()) {
            perms = new LinkedHashSet<>(Collections.singletonList("*"));
            roles = new ArrayList<>();
        } else {
            try {
                perms = new LinkedHashSet<>(rbacService.getUserPermsByUsername(caller));
                roles = new ArrayList<>(rbacService.getUserRoleCodesByUsername(caller));
            } catch (Exception e) {
                log.warn("解析 agent 发起人权限失败, fail-closed 空集: caller={}", caller, e);
                perms = new LinkedHashSet<>();
                roles = new ArrayList<>();
            }
        }
        ctx.setPerms(perms);
        ctx.setRoles(roles);
        ctx.setPermsResolved(true);
    }
}
