package com.datanote.domain.project;

import java.util.*;

/**
 * 发布版本状态机（纯函数，可单测）。轻量单级审批：
 * PENDING →(approve) APPROVED →(release) RELEASED →(rollback) ROLLED_BACK；PENDING →(reject) REJECTED。
 */
public final class ReleaseState {
    private ReleaseState() {}

    private static final Map<String, Set<String>> NEXT = new HashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        NEXT.put("PENDING", new HashSet<>(Arrays.asList("APPROVED", "REJECTED")));
        NEXT.put("APPROVED", new HashSet<>(Collections.singletonList("RELEASED")));
        NEXT.put("RELEASED", new HashSet<>(Collections.singletonList("ROLLED_BACK")));
        NEXT.put("REJECTED", Collections.emptySet());
        NEXT.put("ROLLED_BACK", Collections.emptySet());
        LABELS.put("PENDING", "待审批");
        LABELS.put("APPROVED", "已通过");
        LABELS.put("REJECTED", "已驳回");
        LABELS.put("RELEASED", "已发布");
        LABELS.put("ROLLED_BACK", "已回滚");
    }

    public static boolean canTransition(String from, String to) {
        Set<String> n = NEXT.get(from);
        return n != null && n.contains(to);
    }

    public static String label(String status) {
        return LABELS.getOrDefault(status, status);
    }

    /** 校验流转，非法抛 IllegalArgumentException（统一文案）。 */
    public static void require(String from, String to) {
        if (!canTransition(from, to)) {
            throw new IllegalArgumentException("非法状态流转: " + label(from) + " → " + label(to));
        }
    }
}
