package com.datanote.sync.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlIdentifiersTest {

    @Test
    void quote_wrapsValidIdentifierWithBackticks() {
        assertEquals("`user_id`", SqlIdentifiers.quote("user_id"));
        assertEquals("`Order2`", SqlIdentifiers.quote("Order2"));
    }

    @Test
    void quote_rejectsInjectionAttempts() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("a`b"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("a; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("a b"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote(""));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote(null));
    }

    @Test
    void isValid_returnsBooleanWithoutThrowing() {
        assertTrue(SqlIdentifiers.isValid("col_1"));
        assertFalse(SqlIdentifiers.isValid("col-1"));
    }
}
