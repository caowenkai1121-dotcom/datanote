package com.datanote.platform.iam;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定(防暴力破解)。内存滑动窗口, 无需建表。
 * <p>策略: 同一账号在 {@link #WINDOW_MS} 内累计失败达 {@link #MAX_FAILURES} 次,
 * 锁定 {@link #LOCK_MS}; 锁定期内即便密码正确也拒绝。登录成功立即清零。
 * <p>单实例内存方案; 多实例部署需换 Redis 共享(已在注释标注)。
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** 触发锁定的失败次数阈值。 */
    static final int MAX_FAILURES = 5;
    /** 失败计数滑动窗口(超过则旧失败不再计入)。 */
    static final long WINDOW_MS = 10 * 60 * 1000L;
    /** 锁定时长。 */
    static final long LOCK_MS = 15 * 60 * 1000L;
    /** 毫秒/分钟, 用于日志换算。 */
    private static final long MS_PER_MINUTE = 60 * 1000L;

    private static final class Counter {
        int failures;
        long firstFailAt;
        long lockedUntil;
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** 锁定剩余秒数(>0 表示当前锁定中)。供登录前置检查。 */
    public long lockedRemainingSeconds(String username) {
        Counter c = counters.get(key(username));
        if (c == null) return 0;
        long now = System.currentTimeMillis();
        if (c.lockedUntil > now) return (c.lockedUntil - now + 999) / 1000;
        return 0;
    }

    /** 记一次失败; 返回是否因此进入锁定。 */
    public synchronized boolean recordFailure(String username) {
        String k = key(username);
        long now = System.currentTimeMillis();
        Counter c = counters.computeIfAbsent(k, x -> new Counter());
        // 已锁定中, 维持锁定不重置
        if (c.lockedUntil > now) return true;
        // 窗口过期则重新计数
        if (now - c.firstFailAt > WINDOW_MS) {
            c.failures = 0;
            c.firstFailAt = now;
        }
        if (c.failures == 0) c.firstFailAt = now;
        c.failures++;
        if (c.failures >= MAX_FAILURES) {
            c.lockedUntil = now + LOCK_MS;
            log.warn("账号 {} 登录失败 {} 次, 锁定 {} 分钟", k, c.failures, LOCK_MS / MS_PER_MINUTE);
            return true;
        }
        return false;
    }

    /** 登录成功, 清零该账号失败记录。 */
    public void recordSuccess(String username) {
        counters.remove(key(username));
    }

    /** 当前窗口内剩余可尝试次数(供登录失败时给前端提示)。 */
    public int remainingAttempts(String username) {
        Counter c = counters.get(key(username));
        if (c == null) return MAX_FAILURES;
        long now = System.currentTimeMillis();
        if (c.lockedUntil > now) return 0;
        if (now - c.firstFailAt > WINDOW_MS) return MAX_FAILURES;
        return Math.max(0, MAX_FAILURES - c.failures);
    }
}
