package com.datanote.platform.iam;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败锁定(防暴力破解)。**Redis 优先(跨实例共享)+ 内存兜底**。
 * <p>策略: 同一账号在 {@link #WINDOW_MS} 内累计失败达 {@link #MAX_FAILURES} 次, 锁定 {@link #LOCK_MS};
 * 锁定期内即便密码正确也拒绝。登录成功立即清零。
 * <p>降级铁律: Redis 任何异常 → 回退进程内 ConcurrentHashMap(等同改造前单实例行为, 零回归)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final long WINDOW_MS = 10 * 60 * 1000L;
    static final long LOCK_MS = 15 * 60 * 1000L;
    private static final long MS_PER_MINUTE = 60 * 1000L;

    private static final String FAIL = "login:fail:";
    private static final String LOCK = "login:locked:";

    private final StringRedisTemplate redis;

    private static final class Counter {
        int failures;
        long firstFailAt;
        long lockedUntil;
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** 锁定剩余秒数(>0 表示当前锁定中)。 */
    public long lockedRemainingSeconds(String username) {
        String k = key(username);
        try {
            Long ttl = redis.getExpire(LOCK + k, TimeUnit.SECONDS);
            return ttl != null && ttl > 0 ? ttl : 0;
        } catch (Exception e) {
            return memLockedRemaining(k);
        }
    }

    /** 记一次失败; 返回是否因此进入锁定。 */
    public boolean recordFailure(String username) {
        String k = key(username);
        try {
            if (Boolean.TRUE.equals(redis.hasKey(LOCK + k))) return true;   // 已锁定中, 维持
            Long n = redis.opsForValue().increment(FAIL + k);
            if (n != null && n == 1L) redis.expire(FAIL + k, WINDOW_MS, TimeUnit.MILLISECONDS);
            if (n != null && n >= MAX_FAILURES) {
                redis.opsForValue().set(LOCK + k, "1", LOCK_MS, TimeUnit.MILLISECONDS);
                redis.delete(FAIL + k);
                log.warn("账号 {} 登录失败 {} 次, 锁定 {} 分钟", k, n, LOCK_MS / MS_PER_MINUTE);
                return true;
            }
            return false;
        } catch (Exception e) {
            return memRecordFailure(k);
        }
    }

    /** 登录成功, 清零该账号失败记录。 */
    public void recordSuccess(String username) {
        String k = key(username);
        try { redis.delete(Arrays.asList(FAIL + k, LOCK + k)); } catch (Exception ignore) {}
        counters.remove(k);   // 同步清内存兜底
    }

    /** 当前窗口内剩余可尝试次数。 */
    public int remainingAttempts(String username) {
        String k = key(username);
        try {
            if (Boolean.TRUE.equals(redis.hasKey(LOCK + k))) return 0;
            String v = redis.opsForValue().get(FAIL + k);
            int n = v == null ? 0 : Integer.parseInt(v);
            return Math.max(0, MAX_FAILURES - n);
        } catch (Exception e) {
            return memRemaining(k);
        }
    }

    // ========== 内存兜底(Redis 不可用时, 等同改造前单实例行为) ==========

    private long memLockedRemaining(String k) {
        Counter c = counters.get(k);
        if (c == null) return 0;
        long now = System.currentTimeMillis();
        return c.lockedUntil > now ? (c.lockedUntil - now + 999) / 1000 : 0;
    }

    private synchronized boolean memRecordFailure(String k) {
        long now = System.currentTimeMillis();
        Counter c = counters.computeIfAbsent(k, x -> new Counter());
        if (c.lockedUntil > now) return true;
        if (now - c.firstFailAt > WINDOW_MS) { c.failures = 0; c.firstFailAt = now; }
        if (c.failures == 0) c.firstFailAt = now;
        c.failures++;
        if (c.failures >= MAX_FAILURES) {
            c.lockedUntil = now + LOCK_MS;
            log.warn("账号 {} 登录失败 {} 次, 锁定 {} 分钟(内存兜底)", k, c.failures, LOCK_MS / MS_PER_MINUTE);
            return true;
        }
        return false;
    }

    private int memRemaining(String k) {
        Counter c = counters.get(k);
        if (c == null) return MAX_FAILURES;
        long now = System.currentTimeMillis();
        if (c.lockedUntil > now) return 0;
        if (now - c.firstFailAt > WINDOW_MS) return MAX_FAILURES;
        return Math.max(0, MAX_FAILURES - c.failures);
    }
}
