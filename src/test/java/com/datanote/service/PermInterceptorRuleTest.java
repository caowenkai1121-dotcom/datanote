package com.datanote.platform.iam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PermInterceptor.requiredPerm 纯函数单测: URL→权限点映射(特例优先/GET 放行/敏感 GET)。
 */
class PermInterceptorRuleTest {

    @Test
    void plainGetRequiresNothing() {
        assertNull(PermInterceptor.requiredPerm("GET", "/api/quality/rules"));
        assertNull(PermInterceptor.requiredPerm("GET", "/api/project/list"));
        assertNull(PermInterceptor.requiredPerm("GET", "/api/metadata/preview"));
    }

    @Test
    void sensitiveGetRequiresPerm() {
        assertEquals("governance:audit", PermInterceptor.requiredPerm("GET", "/api/gov/audit/export"));
        assertEquals("settings:user", PermInterceptor.requiredPerm("GET", "/api/rbac/users"));
        assertEquals("settings:user", PermInterceptor.requiredPerm("GET", "/api/rbac/roles"));
        // 权限点清单泄露系统 taxonomy, 收紧到 settings:user(仅配权限的管理员需要)
        assertEquals("settings:user", PermInterceptor.requiredPerm("GET", "/api/rbac/perms/catalog"));
        // /api/rbac/me、usernames(协作选人) 不在敏感 GET 内, 放行
        assertNull(PermInterceptor.requiredPerm("GET", "/api/rbac/me"));
        assertNull(PermInterceptor.requiredPerm("GET", "/api/rbac/usernames"));
        // 调度执行日志含 SQL/库表/连接信息, GET 须 operations:schedule(防按自增 runId 枚举读他人任务日志)
        assertEquals("operations:schedule", PermInterceptor.requiredPerm("GET", "/api/scheduler/run-log/5"));
        assertEquals("operations:schedule", PermInterceptor.requiredPerm("GET", "/api/scheduler/log-detail/12"));
        assertEquals("operations:schedule", PermInterceptor.requiredPerm("GET", "/api/scheduler/logs/3"));
        // 今日状态/运行记录非敏感, 仍只要求登录
        assertNull(PermInterceptor.requiredPerm("GET", "/api/scheduler/today"));
        assertNull(PermInterceptor.requiredPerm("GET", "/api/scheduler/task-runs"));
        // 数据源读: 列表/详情/库/表/列暴露连接配置与源库结构, GET 须 datasource:view
        assertEquals("datasource:view", PermInterceptor.requiredPerm("GET", "/api/datasource/list"));
        assertEquals("datasource:view", PermInterceptor.requiredPerm("GET", "/api/datasource/5"));
        assertEquals("datasource:view", PermInterceptor.requiredPerm("GET", "/api/datasource/5/tables"));
        // 脚本上线审批队列含 payload SQL+申请人, 仅审批人(更具体规则须先于 /api/script 匹配)
        assertEquals("develop:approve", PermInterceptor.requiredPerm("GET", "/api/script/changes"));
        assertEquals("develop:approve", PermInterceptor.requiredPerm("GET", "/api/script/changes/pending-count"));
        // 单脚本详情/版本/树含完整 SQL, GET 须 develop:view
        assertEquals("develop:view", PermInterceptor.requiredPerm("GET", "/api/script/123"));
        assertEquals("develop:view", PermInterceptor.requiredPerm("GET", "/api/script/123/versions"));
        assertEquals("develop:view", PermInterceptor.requiredPerm("GET", "/api/script/tree"));
        assertEquals("develop:view", PermInterceptor.requiredPerm("GET", "/api/script/all-with-content"));
        // 系统配置 / AI 配置读须与写同权 settings:config
        assertEquals("settings:config", PermInterceptor.requiredPerm("GET", "/api/system/config/doris"));
        assertEquals("settings:config", PermInterceptor.requiredPerm("GET", "/api/ai/config"));
        // 数据授权清单读须 data:grant
        assertEquals("data:grant", PermInterceptor.requiredPerm("GET", "/api/data-acl/grants"));
    }

    @Test
    void specialActionRulesBeforeGeneric() {
        assertEquals("mdm:approve", PermInterceptor.requiredPerm("POST", "/api/mdm/approval/5/approve"));
        assertEquals("mdm:approve", PermInterceptor.requiredPerm("POST", "/api/mdm/approval/5/reject"));
        assertEquals("mdm:manage", PermInterceptor.requiredPerm("POST", "/api/mdm/golden/save"));
        assertEquals("dbsync:run", PermInterceptor.requiredPerm("POST", "/api/sync-job/12/run"));
        assertEquals("dbsync:run", PermInterceptor.requiredPerm("POST", "/api/sync-job/12/online"));
        assertEquals("dbsync:edit", PermInterceptor.requiredPerm("POST", "/api/sync-job/save"));
        assertEquals("operations:backfill", PermInterceptor.requiredPerm("POST", "/api/scheduler/backfill"));
        assertEquals("operations:schedule", PermInterceptor.requiredPerm("POST", "/api/scheduler/config/3"));
        assertEquals("operations:baseline", PermInterceptor.requiredPerm("POST", "/api/baseline/create"));
        assertEquals("project:approve", PermInterceptor.requiredPerm("POST", "/api/project/releases/7/approve"));
        assertEquals("assistant:approve", PermInterceptor.requiredPerm("POST", "/api/ai/agent/approval/3/confirm"));
        assertEquals("assistant:use", PermInterceptor.requiredPerm("POST", "/api/ai/agent/chat"));
    }

    @Test
    void genericModuleWritePerms() {
        assertEquals("develop:edit", PermInterceptor.requiredPerm("POST", "/api/script/save"));
        assertEquals("develop:edit", PermInterceptor.requiredPerm("POST", "/api/snippet/save"));
        assertEquals("data:grant", PermInterceptor.requiredPerm("POST", "/api/data-acl/grants"));
        assertEquals("datasource:edit", PermInterceptor.requiredPerm("POST", "/api/datasource/save"));
        assertEquals("datasource:edit", PermInterceptor.requiredPerm("POST", "/api/datasource/test"));
        assertEquals("datasource:edit", PermInterceptor.requiredPerm("DELETE", "/api/datasource/9"));
        assertEquals("develop:edit", PermInterceptor.requiredPerm("DELETE", "/api/snippet/5"));
        assertNull(PermInterceptor.requiredPerm("GET", "/api/snippet/list"));   // 列表只要登录(按用户隔离)
        assertEquals("governance:quality", PermInterceptor.requiredPerm("POST", "/api/quality/rule/save"));
        assertEquals("governance:quality", PermInterceptor.requiredPerm("POST", "/api/quality/rules/batch-status"));
        assertEquals("governance:issue", PermInterceptor.requiredPerm("POST", "/api/gov/health/issues"));
        assertEquals("governance:manage", PermInterceptor.requiredPerm("POST", "/api/gov/masking/policies"));
        assertEquals("metrics:edit", PermInterceptor.requiredPerm("DELETE", "/api/metric/9"));
        assertEquals("project:manage", PermInterceptor.requiredPerm("POST", "/api/project/create"));
        assertEquals("settings:user", PermInterceptor.requiredPerm("POST", "/api/rbac/users"));
        assertEquals("settings:config", PermInterceptor.requiredPerm("POST", "/api/system/config"));
        assertEquals("catalog:edit", PermInterceptor.requiredPerm("POST", "/api/hive/alter-column"));
    }

    @Test
    void unmappedWriteOnlyRequiresLogin() {
        assertNull(PermInterceptor.requiredPerm("POST", "/api/notify/3/read"));
        assertNull(PermInterceptor.requiredPerm("POST", "/api/auth/logout"));
        assertNull(PermInterceptor.requiredPerm("POST", "/not-api/x"));
    }
}
