package com.datanote.service;

import com.datanote.domain.project.ReleaseState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PM-M5：发布状态机纯函数。 */
public class ReleaseStateTest {

    @Test
    void legalTransitions() {
        assertTrue(ReleaseState.canTransition("PENDING", "APPROVED"));
        assertTrue(ReleaseState.canTransition("PENDING", "REJECTED"));
        assertTrue(ReleaseState.canTransition("APPROVED", "RELEASED"));
        assertTrue(ReleaseState.canTransition("RELEASED", "ROLLED_BACK"));
    }

    @Test
    void illegalTransitions() {
        assertFalse(ReleaseState.canTransition("PENDING", "RELEASED"));
        assertFalse(ReleaseState.canTransition("APPROVED", "ROLLED_BACK"));
        assertFalse(ReleaseState.canTransition("REJECTED", "APPROVED"));
        assertFalse(ReleaseState.canTransition("RELEASED", "RELEASED"));
        assertFalse(ReleaseState.canTransition("ROLLED_BACK", "RELEASED"));
        assertFalse(ReleaseState.canTransition("UNKNOWN", "APPROVED"));
    }

    @Test
    void requireThrowsOnIllegal() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseState.require("PENDING", "RELEASED"));
        assertDoesNotThrow(() -> ReleaseState.require("PENDING", "APPROVED"));
    }

    @Test
    void labels() {
        assertEquals("待审批", ReleaseState.label("PENDING"));
        assertEquals("已发布", ReleaseState.label("RELEASED"));
        assertEquals("已回滚", ReleaseState.label("ROLLED_BACK"));
    }
}
