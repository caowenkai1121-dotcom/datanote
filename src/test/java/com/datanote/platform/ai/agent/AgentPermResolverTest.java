package com.datanote.platform.ai.agent;

import com.datanote.platform.ai.agent.engine.AgentPermResolver;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.config.AuthProperties;
import com.datanote.platform.iam.RbacService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPermResolverTest {

    @Test void openModeGivesStar() {
        AuthProperties ap = mock(AuthProperties.class);
        when(ap.isEnabled()).thenReturn(false);
        RbacService rbac = mock(RbacService.class);
        AgentPermResolver r = new AgentPermResolver(rbac, ap);
        AgentContext c = new AgentContext("u", "ip", null, "sid", null);
        r.resolveInto(c, "u");
        assertTrue(c.getPerms().contains("*"));
        assertTrue(c.isPermsResolved());
        verifyNoInteractions(rbac); // 开放模式不查库
    }

    @Test void enabledModeResolvesFromRbac() {
        AuthProperties ap = mock(AuthProperties.class);
        when(ap.isEnabled()).thenReturn(true);
        RbacService rbac = mock(RbacService.class);
        when(rbac.getUserPermsByUsername("alice")).thenReturn(new HashSet<>(Arrays.asList("develop:edit")));
        when(rbac.getUserRoleCodesByUsername("alice")).thenReturn(Arrays.asList("dev"));
        AgentPermResolver r = new AgentPermResolver(rbac, ap);
        AgentContext c = new AgentContext("alice", "ip", null, "sid", null);
        r.resolveInto(c, "alice");
        assertTrue(c.getPerms().contains("develop:edit"));
        assertEquals(Arrays.asList("dev"), c.getRoles());
        assertTrue(c.isPermsResolved());
    }

    @Test void rbacExceptionFailClosedEmptyPerms() {
        AuthProperties ap = mock(AuthProperties.class);
        when(ap.isEnabled()).thenReturn(true);
        RbacService rbac = mock(RbacService.class);
        when(rbac.getUserPermsByUsername(anyString())).thenThrow(new RuntimeException("db down"));
        AgentPermResolver r = new AgentPermResolver(rbac, ap);
        AgentContext c = new AgentContext("bob", "ip", null, "sid", null);
        r.resolveInto(c, "bob");
        assertTrue(c.getPerms().isEmpty());   // fail-closed: 异常→空集(无任何写权限)
        assertTrue(c.isPermsResolved());
    }
}
