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
        // /api/rbac/me 与 perms/catalog 不在敏感 GET 内, 放行
        assertNull(PermInterceptor.requiredPerm("GET", "/api/rbac/me"));
        assertNull(PermInterceptor.requiredPerm("GET", "/api/rbac/perms/catalog"));
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
        assertEquals("governance:quality", PermInterceptor.requiredPerm("POST", "/api/quality/rule/save"));
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
