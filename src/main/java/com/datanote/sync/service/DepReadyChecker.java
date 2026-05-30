package com.datanote.sync.service;

import java.util.List;
import java.util.Map;

/** 依赖就绪判定：所有上游今日最新状态==SUCCESS 才就绪；空依赖恒就绪。 */
public final class DepReadyChecker {
    private DepReadyChecker() {}

    public static boolean allReady(List<Long> upstreamIds, Map<Long, String> latestStatus) {
        if (upstreamIds == null || upstreamIds.isEmpty()) {
            return true;
        }
        for (Long u : upstreamIds) {
            if (!"SUCCESS".equalsIgnoreCase(latestStatus.get(u))) {
                return false;
            }
        }
        return true;
    }
}
