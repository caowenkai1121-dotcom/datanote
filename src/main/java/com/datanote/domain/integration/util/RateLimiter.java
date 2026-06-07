package com.datanote.domain.integration.util;
/** 简单令牌桶限速。reserve 为纯函数(注入 now),acquire 生产用。 */
public final class RateLimiter {
    private final double ratePerSec;
    private double tokens;
    private long lastNanos;
    public RateLimiter(double ratePerSec, long startNanos) {
        this.ratePerSec = ratePerSec <= 0 ? 1 : ratePerSec;
        this.tokens = this.ratePerSec;
        this.lastNanos = startNanos;
    }
    public synchronized long reserve(int n, long nowNanos) {
        double elapsedSec = Math.max(0, (nowNanos - lastNanos) / 1_000_000_000.0);
        tokens = Math.min(ratePerSec, tokens + elapsedSec * ratePerSec);
        lastNanos = nowNanos;
        if (tokens >= n) { tokens -= n; return 0L; }
        double deficit = n - tokens;
        long waitNanos = (long) (deficit / ratePerSec * 1_000_000_000.0);
        tokens = 0;
        lastNanos = nowNanos + waitNanos;
        return waitNanos;
    }
    public void acquire(int n) {
        long w = reserve(n, System.nanoTime());
        if (w > 0) {
            try { Thread.sleep(w / 1_000_000L, (int) (w % 1_000_000L)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
