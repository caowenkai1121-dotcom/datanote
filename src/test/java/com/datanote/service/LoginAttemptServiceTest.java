package com.datanote.platform.iam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoginAttemptService 登录失败锁定逻辑单测。
 */
class LoginAttemptServiceTest {

    @Test
    void locksAfterMaxFailures() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 1; i < LoginAttemptService.MAX_FAILURES; i++) {
            assertFalse(s.recordFailure("bob"), "第 " + i + " 次失败不应锁定");
            assertEquals(0, s.lockedRemainingSeconds("bob"));
        }
        // 第 MAX_FAILURES 次触发锁定
        assertTrue(s.recordFailure("bob"));
        assertTrue(s.lockedRemainingSeconds("bob") > 0);
        assertEquals(0, s.remainingAttempts("bob"));
    }

    @Test
    void successResetsCounter() {
        LoginAttemptService s = new LoginAttemptService();
        s.recordFailure("alice");
        s.recordFailure("alice");
        assertEquals(LoginAttemptService.MAX_FAILURES - 2, s.remainingAttempts("alice"));
        s.recordSuccess("alice");
        assertEquals(LoginAttemptService.MAX_FAILURES, s.remainingAttempts("alice"));
        assertEquals(0, s.lockedRemainingSeconds("alice"));
    }

    @Test
    void caseInsensitiveAndTrimmed() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) s.recordFailure(" Bob ");
        // 大小写/空格归一: BOB 同一账号视为锁定
        assertTrue(s.lockedRemainingSeconds("bob") > 0);
        assertTrue(s.lockedRemainingSeconds("BOB") > 0);
    }

    @Test
    void independentAccounts() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) s.recordFailure("x");
        assertTrue(s.lockedRemainingSeconds("x") > 0);
        assertEquals(0, s.lockedRemainingSeconds("y"));
        assertEquals(LoginAttemptService.MAX_FAILURES, s.remainingAttempts("y"));
    }
}
