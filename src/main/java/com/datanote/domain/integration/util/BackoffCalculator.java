package com.datanote.domain.integration.util;
/** 重试退避延迟计算。 */
public final class BackoffCalculator {
    /** long 左移安全位数上限:位移量 >= 此值时已远超 maxSec,直接返回上限避免溢出。 */
    private static final int MAX_SHIFT = 31;
    private BackoffCalculator() {}
    public static int delaySeconds(int attempt, String type, int baseSec, int maxSec) {
        if (attempt < 1) attempt = 1;
        if ("EXPONENTIAL".equalsIgnoreCase(type)) {
            // 位移量封顶:attempt 过大时 long 左移按 &0x3f 取模会溢出成负数或位移归零,直接返回上限
            if (attempt - 1 >= MAX_SHIFT) return maxSec;
            long d = (long) baseSec << (attempt - 1);
            return (int) Math.min(maxSec, Math.max(0, d));
        }
        return Math.min(maxSec, baseSec);
    }
}
