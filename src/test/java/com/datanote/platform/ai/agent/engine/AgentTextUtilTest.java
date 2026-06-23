package com.datanote.platform.ai.agent.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AgentTextUtil.redactSecrets 凭据脱敏单测(落地禁写凭据红线; 既有 AgentTextUtilTest 未覆盖脱敏, 此处补齐)。 */
class AgentTextUtilTest {

    @Test
    void redactsKeyValueSecrets() {
        assertFalse(AgentTextUtil.redactSecrets("password=hunter2abc").contains("hunter2abc"));
        assertFalse(AgentTextUtil.redactSecrets("{\"token\":\"abcd1234efgh\"}").contains("abcd1234efgh"));
        assertFalse(AgentTextUtil.redactSecrets("api_key: sk_live_xyz987").contains("sk_live_xyz987"));
    }

    @Test
    void redactsBearerAndPrefixKeys() {
        assertFalse(AgentTextUtil.redactSecrets("Authorization: Bearer abcdefgh12345678").contains("abcdefgh12345678"));
        assertFalse(AgentTextUtil.redactSecrets("key sk-ABCDEFGH12345678 used").contains("sk-ABCDEFGH12345678"));
    }

    @Test
    void redactsConnStringPassword() {
        assertFalse(AgentTextUtil.redactSecrets("jdbc://admin:topSecret9@db:3306").contains("topSecret9"));
    }

    @Test
    void redactNullSafe() {
        assertDoesNotThrow(() -> AgentTextUtil.redactSecrets(null));
    }
}
