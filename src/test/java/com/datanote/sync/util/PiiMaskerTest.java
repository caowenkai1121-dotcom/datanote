package com.datanote.sync.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PiiMaskerTest {

    @Test
    void nullTypePassthrough() {
        assertEquals("x", PiiMasker.mask("x", null, null));
    }

    @Test
    void nullValuePassthrough() {
        assertNull(PiiMasker.mask(null, "PHONE", null));
    }

    @Test
    void phone() {
        assertEquals("138****8000", PiiMasker.mask("13812348000", "PHONE", null));
    }

    @Test
    void email() {
        assertEquals("a***@b.com", PiiMasker.mask("abc@b.com", "EMAIL", null));
    }

    @Test
    void idcard() {
        assertEquals("110***********1234", PiiMasker.mask("110101199001011234", "IDCARD", null));
    }

    @Test
    void redact() {
        assertEquals("***", PiiMasker.mask("anything", "REDACT", null));
    }

    @Test
    void hashDeterministic() {
        Object r1 = PiiMasker.mask("secret", "HASH_SHA256", "salt");
        Object r2 = PiiMasker.mask("secret", "HASH_SHA256", "salt");
        assertEquals(r1, r2);
        assertNotEquals("secret", r1);
        assertEquals(64, r1.toString().length());
    }

    // === 防御性 bug 修复用例（Group B）===

    @Test
    void emailAtStartRedacted() {
        assertEquals("***", PiiMasker.mask("@b.com", "EMAIL", null));
    }

    @Test
    void emailNoAtRedacted() {
        assertEquals("***", PiiMasker.mask("noemail", "EMAIL", null));
    }

    @Test
    void idcardLen7Redacted() {
        assertEquals("***", PiiMasker.mask("1234567", "IDCARD", null));
    }
}
