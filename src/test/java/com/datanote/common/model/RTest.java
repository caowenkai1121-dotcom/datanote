package com.datanote.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RTest {

    @Test
    void fail_redactsSecretsInMessage() {
        R<?> response = R.fail("连接失败 password=plain-secret");

        assertFalse(response.getMsg().contains("plain-secret"));
        assertTrue(response.getMsg().contains("***REDACTED***"));
    }

    @Test
    void failWithCode_redactsSecretsInMessage() {
        R<?> response = R.fail(R.CODE_BAD_REQUEST, "token=abcdef123456");

        assertFalse(response.getMsg().contains("abcdef123456"));
        assertTrue(response.getMsg().contains("***REDACTED***"));
    }
}
