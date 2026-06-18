package com.datanote.util;

import com.datanote.common.util.SecretRedactor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    @Test
    void redact_hidesCommonCredentialFormats() {
        String text = "password=plain-secret token: abcdef123456 jdbc:mysql://root:db-secret@127.0.0.1/db Bearer bearer-secret";

        String redacted = SecretRedactor.redact(text);

        assertFalse(redacted.contains("plain-secret"));
        assertFalse(redacted.contains("abcdef123456"));
        assertFalse(redacted.contains("db-secret"));
        assertFalse(redacted.contains("bearer-secret"));
        assertTrue(redacted.contains("***REDACTED***"));
    }

    @Test
    void redact_nullReturnsNull() {
        assertNull(SecretRedactor.redact(null));
    }
}
