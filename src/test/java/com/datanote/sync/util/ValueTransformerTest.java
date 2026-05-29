package com.datanote.sync.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueTransformerTest {

    @Test
    void nullExprPassthrough() {
        assertEquals("abc", ValueTransformer.transform("abc", null));
        assertEquals("abc", ValueTransformer.transform("abc", ""));
    }

    @Test
    void nullValuePassthrough() {
        assertNull(ValueTransformer.transform(null, "{\"type\":\"upper\"}"));
    }

    @Test
    void upperLowerTrim() {
        assertEquals("ABC", ValueTransformer.transform("abc", "{\"type\":\"upper\"}"));
        assertEquals("abc", ValueTransformer.transform("ABC", "{\"type\":\"lower\"}"));
        assertEquals("ab", ValueTransformer.transform(" ab ", "{\"type\":\"trim\"}"));
    }

    @Test
    void substring() {
        assertEquals("hel", ValueTransformer.transform("hello", "{\"type\":\"substring\",\"args\":{\"start\":0,\"length\":3}}"));
    }

    @Test
    void substringOutOfRangeClamped() {
        assertEquals("lo", ValueTransformer.transform("hello", "{\"type\":\"substring\",\"args\":{\"start\":3,\"length\":99}}"));
    }

    @Test
    void replace() {
        assertEquals("a-b", ValueTransformer.transform("a_b", "{\"type\":\"replace\",\"args\":{\"from\":\"_\",\"to\":\"-\"}}"));
    }

    @Test
    void lpad() {
        assertEquals("007", ValueTransformer.transform("7", "{\"type\":\"lpad\",\"args\":{\"len\":3,\"pad\":\"0\"}}"));
    }
}
