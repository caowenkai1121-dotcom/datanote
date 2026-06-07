package com.datanote.domain.integration.controller;

import com.datanote.domain.orchestration.model.DnTaskExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M3：大盘 run 级聚合纯函数——成功率 / 今日写入行 / 成功失败计数。 */
public class SyncJobDashboardKpiTest {

    private DnTaskExecution exec(String status, Long write) {
        DnTaskExecution e = new DnTaskExecution();
        e.setStatus(status);
        e.setWriteCount(write);
        return e;
    }

    @Test
    void successRate() {
        List<DnTaskExecution> recent = Arrays.asList(
                exec("SUCCESS", 10L), exec("SUCCESS", 20L), exec("FAILED", 0L), exec("SUCCESS", 5L));
        Map<String, Object> m = SyncJobController.aggregateRuns(recent, Collections.emptyList());
        assertEquals(0.75, (Double) m.get("successRate"), 1e-9);
    }

    @Test
    void emptyRecentRateNull() {
        Map<String, Object> m = SyncJobController.aggregateRuns(new ArrayList<>(), new ArrayList<>());
        assertNull(m.get("successRate"));
        assertEquals(0L, m.get("runsToday"));
        assertEquals(0L, m.get("rowsToday"));
    }

    @Test
    void todayAggregation() {
        List<DnTaskExecution> today = Arrays.asList(
                exec("SUCCESS", 100L), exec("FAILED", null), exec("SUCCESS", 50L));
        Map<String, Object> m = SyncJobController.aggregateRuns(Collections.emptyList(), today);
        assertEquals(3L, m.get("runsToday"));
        assertEquals(150L, m.get("rowsToday"), "null writeCount 视为 0");
        assertEquals(2L, m.get("successToday"));
        assertEquals(1L, m.get("failedToday"));
    }
}
