package com.datanote.domain.governance;

import com.datanote.domain.governance.QualityScheduleService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量调度 cron "本分钟该跑" 判定纯函数单测。
 * cron 在 [本分钟起点, 下一分钟起点) 区间内有触发点则应跑。
 */
class QualityScheduleDueTest {

    @Test
    void everyMinuteCronAlwaysDue() {
        // 每分钟第 0 秒触发：任何分钟都该跑
        LocalDateTime now = LocalDateTime.of(2026, 5, 30, 10, 23, 12);
        assertTrue(QualityScheduleService.isDueThisMinute("0 * * * * ?", now), "每分钟 cron 本分钟应触发");
    }

    @Test
    void matchingMinuteIsDue() {
        // 每天 10:23 触发，当前正处于 10:23 分内
        LocalDateTime now = LocalDateTime.of(2026, 5, 30, 10, 23, 5);
        assertTrue(QualityScheduleService.isDueThisMinute("0 23 10 * * ?", now), "命中分钟应触发");
    }

    @Test
    void nonMatchingMinuteNotDue() {
        // 每天 10:23 触发，当前在 10:24 分，本分钟不该跑
        LocalDateTime now = LocalDateTime.of(2026, 5, 30, 10, 24, 5);
        assertFalse(QualityScheduleService.isDueThisMinute("0 23 10 * * ?", now), "未命中分钟不应触发");
    }

    @Test
    void blankCronNotDue() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 30, 10, 23, 0);
        assertFalse(QualityScheduleService.isDueThisMinute("", now), "空 cron 不应触发");
        assertFalse(QualityScheduleService.isDueThisMinute(null, now), "null cron 不应触发");
    }

    @Test
    void invalidCronNotDue() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 30, 10, 23, 0);
        assertFalse(QualityScheduleService.isDueThisMinute("not a cron", now), "非法 cron 不应触发");
    }
}
