package com.datanote.service;

import com.datanote.domain.governance.OverviewService;
import com.datanote.domain.governance.model.DnQualityRun;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 治理总览近期通过率纯函数单测 —— 仅计 SUCCESS 且 passRate 非空，pass_rate 落库即 0-100 百分数，直接取均值保留 1 位。
 */
class OverviewRecentPassRateTest {

    private DnQualityRun run(String status, Double passRate) {
        DnQualityRun r = new DnQualityRun();
        r.setRunStatus(status);
        r.setPassRate(passRate == null ? null : BigDecimal.valueOf(passRate));
        return r;
    }

    @Test
    void averageOfSuccessRuns() {
        List<DnQualityRun> runs = Arrays.asList(
                run("SUCCESS", 100.0),
                run("SUCCESS", 80.0));
        // (100 + 80)/2 = 90.0
        assertEquals(90.0, OverviewService.recentPassRate(runs), 1e-9);
    }

    @Test
    void nonSuccessAndNullPassRateExcluded() {
        List<DnQualityRun> runs = Arrays.asList(
                run("SUCCESS", 90.0),
                run("FAILED", 10.0),      // 非 SUCCESS 剔除
                run("SUCCESS", null));    // passRate 空剔除
        // 仅 90.0 计入 → 90.0
        assertEquals(90.0, OverviewService.recentPassRate(runs), 1e-9);
    }

    @Test
    void emptyOrNullReturnsZero() {
        assertEquals(0.0, OverviewService.recentPassRate(new ArrayList<>()), 1e-9);
        assertEquals(0.0, OverviewService.recentPassRate(null), 1e-9);
    }

    @Test
    void roundedToOneDecimal() {
        List<DnQualityRun> runs = Arrays.asList(
                run("SUCCESS", 100.0),
                run("SUCCESS", 100.0),
                run("SUCCESS", 0.0));
        // (100+100+0)/3 = 66.666... → 66.7
        assertEquals(66.7, OverviewService.recentPassRate(runs), 1e-9);
    }
}
