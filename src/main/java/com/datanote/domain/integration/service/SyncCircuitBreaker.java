package com.datanote.domain.integration.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步熔断器（守卫式, 默认关闭）。按"数据源键"维护失败计数, 连续失败达阈值则熔断快速失败一段冷却期,
 * 避免某源库/目标过载(too many connections / 宕机)时大量任务持续重试踩踏。冷却后半开放行一次试探。
 * 默认 enabled=false 完全不介入(零行为变更); 需 datanote.sync.circuit-breaker.enabled=true 开启。
 */
@Component
public class SyncCircuitBreaker {

    @Value("${datanote.sync.circuit-breaker.enabled:false}")
    private boolean enabled;
    /** 连续失败达此数则熔断 */
    @Value("${datanote.sync.circuit-breaker.threshold:5}")
    private int threshold;
    /** 熔断冷却毫秒(期内对该源快速失败) */
    @Value("${datanote.sync.circuit-breaker.cooldown-ms:60000}")
    private long cooldownMs;

    private static final class State {
        final AtomicInteger failures = new AtomicInteger(0);
        volatile long openUntil = 0L;
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /** 是否放行(true=可执行)。熔断打开且未到冷却期则 false。enabled=false 恒放行。 */
    public boolean allow(String key) {
        if (!enabled || key == null) return true;
        State s = states.get(key);
        if (s == null) return true;
        return System.currentTimeMillis() >= s.openUntil;   // 冷却期过=半开放行试探
    }

    /** 记录一次失败; 连续失败达阈值则打开熔断(置冷却期), 并重置计数等待半开。 */
    public void recordFailure(String key) {
        if (!enabled || key == null) return;
        State s = states.computeIfAbsent(key, k -> new State());
        if (s.failures.incrementAndGet() >= threshold) {
            s.openUntil = System.currentTimeMillis() + cooldownMs;
            s.failures.set(0);
        }
    }

    /** 记录一次成功: 清零失败计数并关闭熔断(半开试探成功即恢复)。 */
    public void recordSuccess(String key) {
        if (!enabled || key == null) return;
        State s = states.get(key);
        if (s != null) { s.failures.set(0); s.openUntil = 0L; }
    }

    public boolean isEnabled() { return enabled; }
}
