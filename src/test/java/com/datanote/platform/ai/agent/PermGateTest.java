package com.datanote.platform.ai.agent;

import com.datanote.platform.ai.agent.engine.PermGate;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class PermGateTest {

    /** 极简假工具: 指定 name/requiredPerm。 */
    private AiTool tool(final String name, final String perm) {
        return new AiTool() {
            public String name() { return name; }
            public String group() { return "test"; }
            public String description() { return ""; }
            public String paramsSchemaJson() { return "{}"; }
            public boolean readOnly() { return true; }
            public RiskLevel risk() { return RiskLevel.LOW; }
            public AiToolResult invoke(JsonNode args, AgentContext ctx) { return AiToolResult.ok(null); }
            public String requiredPerm() { return perm; }
        };
    }

    private AgentContext ctxWith(String... perms) {
        AgentContext c = new AgentContext("u", "ip", null, "sid", null);
        c.setPerms(new HashSet<>(Arrays.asList(perms)));
        return c;
    }

    @Test void nullPermAllowsAnyLoggedInUser() {
        assertEquals(PermGate.Decision.ALLOW, PermGate.check(tool("t", null), ctxWith()));
    }

    @Test void exactPermAllows() {
        assertEquals(PermGate.Decision.ALLOW, PermGate.check(tool("t", "develop:edit"), ctxWith("develop:edit")));
    }

    @Test void missingPermDenies() {
        assertEquals(PermGate.Decision.DENY, PermGate.check(tool("t", "develop:edit"), ctxWith("develop:view")));
    }

    @Test void starAllowsEverything() {
        assertEquals(PermGate.Decision.ALLOW, PermGate.check(tool("t", "develop:edit"), ctxWith("*")));
    }

    @Test void unresolvedPermsFailClosedWhenPermRequired() {
        AgentContext c = new AgentContext("u", "ip", null, "sid", null); // perms=null
        assertEquals(PermGate.Decision.DENY, PermGate.check(tool("t", "develop:edit"), c));
    }

    @Test void unresolvedPermsStillAllowsNullPermTool() {
        AgentContext c = new AgentContext("u", "ip", null, "sid", null);
        assertEquals(PermGate.Decision.ALLOW, PermGate.check(tool("t", null), c));
    }

    @Test void nullCtxFailClosedWhenPermRequired() {
        assertEquals(PermGate.Decision.DENY, PermGate.check(tool("t", "develop:edit"), null));
    }
}
