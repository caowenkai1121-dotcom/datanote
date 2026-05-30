package com.datanote.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 质量判定纯函数单测：通过率 >= 阈值 即 success，否则 failed。
 */
class QualityJudgeStatusTest {

    @Test
    void rateEqualThresholdIsSuccess() {
        assertEquals("success", QualityService.judgeStatus(bd(95), bd(95)), "通过率=阈值应为 success");
    }

    @Test
    void rateAboveThresholdIsSuccess() {
        assertEquals("success", QualityService.judgeStatus(bd(99.5), bd(95)), "通过率>阈值应为 success");
    }

    @Test
    void rateBelowThresholdIsFailed() {
        assertEquals("failed", QualityService.judgeStatus(bd(94.99), bd(95)), "通过率<阈值应为 failed");
    }

    @Test
    void nullThresholdDefaultsTo100() {
        // 阈值缺省视为 100：必须满分才 success（保持旧"零失败才过"语义）
        assertEquals("success", QualityService.judgeStatus(bd(100), null), "100% 通过且无阈值应为 success");
        assertEquals("failed", QualityService.judgeStatus(bd(99.99), null), "未满分且无阈值应为 failed");
    }

    @Test
    void fullPassAlwaysSuccess() {
        assertEquals("success", QualityService.judgeStatus(bd(100), bd(100)), "100%/阈值100 应为 success");
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
