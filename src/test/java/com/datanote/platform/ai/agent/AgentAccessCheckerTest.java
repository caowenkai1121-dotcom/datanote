package com.datanote.platform.ai.agent;

import com.datanote.platform.ai.agent.engine.AgentAccessChecker;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.iam.DataAclService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentAccessCheckerTest {

    private final ObjectMapper om = new ObjectMapper();

    private AgentContext ctx() {
        AgentContext c = new AgentContext("bob", "ip", null, "sid", null);
        c.setPerms(new java.util.HashSet<>());
        c.setRoles(new java.util.ArrayList<>());
        return c;
    }

    @Test void nonTableScopedToolAllows() throws Exception {
        DataAclService acl = mock(DataAclService.class);
        AgentAccessChecker chk = new AgentAccessChecker(acl);
        assertNull(chk.dataDeny("gov_overview", om.readTree("{}"), ctx()));
        verifyNoInteractions(acl);
    }

    @Test void tableScopedDeniedReturnsReason() throws Exception {
        DataAclService acl = mock(DataAclService.class);
        when(acl.canAccessAs(eq("bob"), any(), any(), eq("TABLE"), eq("ods.t1"))).thenReturn(false);
        AgentAccessChecker chk = new AgentAccessChecker(acl);
        String reason = chk.dataDeny("table_data", om.readTree("{\"db\":\"ods\",\"table\":\"t1\"}"), ctx());
        assertNotNull(reason);
    }

    @Test void tableScopedAllowedReturnsNull() throws Exception {
        DataAclService acl = mock(DataAclService.class);
        when(acl.canAccessAs(eq("bob"), any(), any(), eq("TABLE"), eq("ods.t1"))).thenReturn(true);
        AgentAccessChecker chk = new AgentAccessChecker(acl);
        assertNull(chk.dataDeny("asset_detail", om.readTree("{\"db\":\"ods\",\"table\":\"t1\"}"), ctx()));
    }

    @Test void tableScopedMissingArgsAllows() throws Exception {
        DataAclService acl = mock(DataAclService.class);
        AgentAccessChecker chk = new AgentAccessChecker(acl);
        // 无 db/table 无从判定 → 不拦(交由工具自身缺参处理), 避免误伤
        assertNull(chk.dataDeny("table_data", om.readTree("{}"), ctx()));
    }
}
