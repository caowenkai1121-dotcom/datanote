package com.datanote.platform.ai.agent;

import com.datanote.platform.config.AuthProperties;
import com.datanote.platform.iam.DataAclService;
import com.datanote.platform.iam.RbacService;
import com.datanote.platform.iam.mapper.DnDataGrantMapper;
import com.datanote.platform.iam.model.DnDataGrant;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataAclServiceAsTest {

    private DnDataGrant grant(String type, String id, String pt, String principal) {
        DnDataGrant g = new DnDataGrant();
        g.setResourceType(type); g.setResourceId(id); g.setPrincipalType(pt); g.setPrincipal(principal);
        return g;
    }

    @Test void noGrantIsPublic() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        when(m.selectList(any())).thenReturn(Collections.emptyList());
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        assertTrue(svc.canAccessAs("bob", Arrays.asList("dev"), java.util.Collections.<String>emptySet(), "TABLE", "ods.t1"));
    }

    @Test void restrictedDeniesUngranted() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        when(m.selectList(any())).thenReturn(Arrays.asList(grant("TABLE", "ods.t1", "USER", "alice")));
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        assertFalse(svc.canAccessAs("bob", Collections.<String>emptyList(), Collections.<String>emptySet(), "TABLE", "ods.t1"));
        assertTrue(svc.canAccessAs("alice", Collections.<String>emptyList(), Collections.<String>emptySet(), "TABLE", "ods.t1"));
    }

    @Test void roleGrantAllows() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        when(m.selectList(any())).thenReturn(Arrays.asList(grant("TABLE", "ods.t1", "ROLE", "analyst")));
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        assertTrue(svc.canAccessAs("bob", Arrays.asList("analyst"), Collections.<String>emptySet(), "TABLE", "ods.t1"));
    }

    @Test void dataAllBypasses() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        when(m.selectList(any())).thenReturn(Arrays.asList(grant("TABLE", "ods.t1", "USER", "alice")));
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        assertTrue(svc.canAccessAs("bob", Collections.<String>emptyList(),
                new java.util.HashSet<>(Arrays.asList("data:all")), "TABLE", "ods.t1"));
    }

    @Test void anonymousDoesNotBypassRestrictedResource() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        when(m.selectList(any())).thenReturn(Arrays.asList(grant("TABLE", "ods.t1", "USER", "alice")));
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        assertFalse(svc.canAccessAs("anonymous", Collections.<String>emptyList(),
                Collections.<String>emptySet(), "TABLE", "ods.t1"));
    }

    @Test void adminNameDoesNotBypassWithoutPermission() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        when(m.selectList(any())).thenReturn(Arrays.asList(grant("TABLE", "ods.t1", "USER", "alice")));
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        assertFalse(svc.canAccessAs("admin", Collections.<String>emptyList(),
                Collections.<String>emptySet(), "TABLE", "ods.t1"));
    }

    @Test void deniedIdsReturnsOnlyRestrictedUngranted() {
        DnDataGrantMapper m = mock(DnDataGrantMapper.class);
        // r1 授权给 alice, r2 授权给 bob; 公开资源(无授权)不会出现在 all 里
        when(m.selectList(any())).thenReturn(Arrays.asList(
                grant("TABLE", "ods.r1", "USER", "alice"),
                grant("TABLE", "ods.r2", "USER", "bob")));
        DataAclService svc = new DataAclService(m, mock(RbacService.class), enabledAuth());
        java.util.Set<String> denied = svc.deniedIdsAs("alice", Collections.<String>emptyList(),
                Collections.<String>emptySet(), "TABLE");
        assertTrue(denied.contains("ods.r2"));   // alice 不在 r2 名单 → 受限
        assertFalse(denied.contains("ods.r1"));  // alice 在 r1 名单 → 可见
    }

    private AuthProperties enabledAuth() {
        AuthProperties ap = mock(AuthProperties.class);
        when(ap.isEnabled()).thenReturn(true);
        return ap;
    }
}
