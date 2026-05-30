package com.datanote.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期纯函数单测 —— 无用表四要素打分 / 成本估算 / 销毁到期 / Doris DDL 构造，无 Spring 上下文。
 */
class LifecycleScorerTest {

    // ========== 无用表四要素打分 ==========

    @Test
    void unusedCandidateWhenLongIdleNoDownstreamNoTaskRef() {
        // 久未访问 + 大体量 + 无下游血缘 + 无任务引用 -> 候选，高分
        LifecycleScorer.UnusedScore s = LifecycleScorer.scoreUnusedTable(
                200, 50L * 1024 * 1024 * 1024, false, false);
        assertTrue(s.candidate, "四要素齐备应判为候选");
        assertTrue(s.score >= 70, "应为高分，实际=" + s.score);
    }

    @Test
    void notCandidateWhenHasDownstreamLineage() {
        // 有下游血缘 -> 绝不判候选（销毁第一道护栏的判定面）
        LifecycleScorer.UnusedScore s = LifecycleScorer.scoreUnusedTable(
                999, 100L * 1024 * 1024 * 1024, true, false);
        assertFalse(s.candidate, "有下游血缘不应判候选");
    }

    @Test
    void notCandidateWhenHasTaskRef() {
        LifecycleScorer.UnusedScore s = LifecycleScorer.scoreUnusedTable(
                999, 100L * 1024 * 1024 * 1024, false, true);
        assertFalse(s.candidate, "有任务引用不应判候选");
    }

    @Test
    void notCandidateWhenRecentlyAccessed() {
        // 近期访问过 -> 不候选
        LifecycleScorer.UnusedScore s = LifecycleScorer.scoreUnusedTable(
                3, 100L * 1024 * 1024 * 1024, false, false);
        assertFalse(s.candidate, "近期访问不应判候选");
    }

    @Test
    void scoreMonotonicWithIdleDays() {
        long size = 1024L * 1024 * 1024;
        int more = LifecycleScorer.scoreUnusedTable(365, size, false, false).score;
        int less = LifecycleScorer.scoreUnusedTable(100, size, false, false).score;
        assertTrue(more >= less, "越久未访问分越高");
    }

    // ========== 成本估算 ==========

    @Test
    void estimateCostByGbTimesUnitPrice() {
        // 2 GB × 0.05 元/GB/月 = 0.10
        double cost = LifecycleScorer.estimateCost(2L * 1024 * 1024 * 1024, 0.05);
        assertEquals(0.10, cost, 0.0001);
    }

    @Test
    void estimateCostZeroWhenNullOrNegative() {
        assertEquals(0.0, LifecycleScorer.estimateCost(0, 0.05), 0.0001);
        assertEquals(0.0, LifecycleScorer.estimateCost(-1, 0.05), 0.0001);
    }

    // ========== 销毁宽限期到期 ==========

    @Test
    void dropDueAtAddsGraceDays() {
        LocalDateTime marked = LocalDateTime.of(2026, 5, 30, 10, 0, 0);
        assertEquals(LocalDateTime.of(2026, 6, 29, 10, 0, 0),
                LifecycleScorer.dropDueAt(marked, 30));
    }

    // ========== Doris DDL 构造（应用策略下发的内容，可单测） ==========

    @Test
    void buildTtlDdlUsesDynamicPartition() {
        String ddl = LifecycleScorer.buildDorisDdl("ods", "t_order", "TTL", null, 30);
        assertTrue(ddl.contains("`ods`.`t_order`"), "应限定库表");
        assertTrue(ddl.toLowerCase().contains("dynamic_partition"), "TTL 走 dynamic_partition: " + ddl);
        assertTrue(ddl.contains("30") || ddl.contains("-30"), "应含保留天数: " + ddl);
    }

    @Test
    void buildHotColdDdlUsesStoragePolicy() {
        String ddl = LifecycleScorer.buildDorisDdl("ods", "t_log", "HOT_COLD", 7, null);
        assertTrue(ddl.contains("`ods`.`t_log`"));
        assertTrue(ddl.toLowerCase().contains("storage_policy") || ddl.toLowerCase().contains("storage policy"),
                "冷热分层应下发 storage policy: " + ddl);
    }

    @Test
    void buildDdlRejectsIllegalIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> LifecycleScorer.buildDorisDdl("ods; DROP", "t", "TTL", null, 30),
                "非法库名应拒绝，防注入");
    }
}
