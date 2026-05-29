package com.datanote.sync.util;
/** 重试退避延迟计算。 */
public final class BackoffCalculator {
    private BackoffCalculator() {}
    public static int delaySeconds(int attempt, String type, int baseSec, int maxSec) {
        if (attempt < 1) attempt = 1;
        if ("EXPONENTIAL".equalsIgnoreCase(type)) {
            long d = (long) baseSec << (attempt - 1);
            return (int) Math.min(maxSec, d);
        }
        return Math.min(maxSec, baseSec);
    }
}
