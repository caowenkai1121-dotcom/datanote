package com.datanote.sync.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DepReadyChecker 依赖就绪判定单测。
 */
class DepReadyCheckerTest {

    @Test
    void emptyDepsReady() {
        assertTrue(DepReadyChecker.allReady(null, Collections.emptyMap()));
        assertTrue(DepReadyChecker.allReady(Collections.emptyList(), Collections.emptyMap()));
    }

    @Test
    void allSuccessReady() {
        Map<Long, String> status = new HashMap<>();
        status.put(1L, "SUCCESS");
        status.put(2L, "SUCCESS");
        assertTrue(DepReadyChecker.allReady(Arrays.asList(1L, 2L), status));
    }

    @Test
    void anyNonSuccessNotReady() {
        Map<Long, String> status = new HashMap<>();
        status.put(1L, "SUCCESS");
        status.put(2L, "FAILED");
        assertFalse(DepReadyChecker.allReady(Arrays.asList(1L, 2L), status));
    }

    @Test
    void missingUpstreamNotReady() {
        Map<Long, String> status = new HashMap<>();
        status.put(1L, "SUCCESS");
        // 上游 2 没有今日执行记录 → 缺失视为未就绪
        assertFalse(DepReadyChecker.allReady(Arrays.asList(1L, 2L), status));
    }
}
