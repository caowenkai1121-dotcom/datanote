package com.datanote.domain.integration.connector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JdbcExtraParamsSanitizer 危险驱动属性过滤单测(P2-04)。 */
class JdbcExtraParamsSanitizerTest {

    @Test
    void benignParamsKept() {
        assertEquals("a=1&b=2", JdbcExtraParamsSanitizer.sanitize("a=1&b=2", '&'));
        assertEquals("connectTimeout=5000", JdbcExtraParamsSanitizer.sanitize("connectTimeout=5000", '&'));
    }

    @Test
    void dangerousKeysDropped() {
        assertEquals("a=1&b=2", JdbcExtraParamsSanitizer.sanitize("a=1&allowLoadLocalInfile=true&b=2", '&'));
        assertEquals("a=1", JdbcExtraParamsSanitizer.sanitize("a=1&autoDeserialize=true", '&'));
        assertEquals("a=1", JdbcExtraParamsSanitizer.sanitize("allowMultiQueries=true&a=1", '&'));
        assertEquals("k1=v", JdbcExtraParamsSanitizer.sanitize("k1=v;allowMultiQueries=true", ';'));
    }

    @Test
    void commentAndEmptyHandled() {
        assertEquals("a=1", JdbcExtraParamsSanitizer.sanitize("a=1&b=2#evil", '&'));   // 含 # 项丢弃
        assertEquals("", JdbcExtraParamsSanitizer.sanitize(null, '&'));
        assertEquals("", JdbcExtraParamsSanitizer.sanitize("   ", '&'));
    }
}
