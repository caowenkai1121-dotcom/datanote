package com.datanote.sync.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyncJobScheduler.shouldFire() 单测。
 * 不依赖 Spring 上下文，纯逻辑验证。
 */
class SyncJobSchedulerTest {

    /**
     * 每分钟整秒触发（Spring 6位 cron: 秒 分 时 日 月 周）
     * "0 * * * * *" = 每分钟第0秒
     */
    private static final String CRON_EVERY_MINUTE = "0 * * * * *";

    @Test
    void shouldFire_whenCronTriggeredBetweenLastFireAndNow_returnsTrue() {
        // lastFire = :00:30，now = :01:10，中间经过了 :01:00 整分 → 应触发
        LocalDateTime lastFire = LocalDateTime.of(2026, 5, 27, 10, 0, 30);
        LocalDateTime now      = LocalDateTime.of(2026, 5, 27, 10, 1, 10);
        assertTrue(SyncJobScheduler.shouldFire(CRON_EVERY_MINUTE, lastFire, now),
                "跨过整分应返回 true");
    }

    @Test
    void shouldFire_whenNoTriggerInInterval_returnsFalse() {
        // lastFire = :01:00（刚触发过），now = :01:20（同分钟内） → 不应重复触发
        LocalDateTime lastFire = LocalDateTime.of(2026, 5, 27, 10, 1, 0);
        LocalDateTime now      = LocalDateTime.of(2026, 5, 27, 10, 1, 20);
        assertFalse(SyncJobScheduler.shouldFire(CRON_EVERY_MINUTE, lastFire, now),
                "同分钟内重复 tick 应返回 false");
    }

    @Test
    void shouldFire_whenCronIsNull_returnsFalse() {
        LocalDateTime lastFire = LocalDateTime.of(2026, 5, 27, 10, 0, 0);
        LocalDateTime now      = LocalDateTime.of(2026, 5, 27, 10, 5, 0);
        assertFalse(SyncJobScheduler.shouldFire(null, lastFire, now),
                "cron 为 null 应返回 false");
    }

    @Test
    void shouldFire_whenNowEqualsNextTrigger_returnsTrue() {
        // now 恰好 = 下次触发时间点（边界情况，next <= now）
        // lastFire = 09:59:30，cron 整分，next=10:00:00，now=10:00:00 → 应触发
        LocalDateTime lastFire = LocalDateTime.of(2026, 5, 27, 9, 59, 30);
        LocalDateTime now      = LocalDateTime.of(2026, 5, 27, 10, 0, 0);
        assertTrue(SyncJobScheduler.shouldFire(CRON_EVERY_MINUTE, lastFire, now),
                "now 恰好等于触发时间点应返回 true");
    }
}
