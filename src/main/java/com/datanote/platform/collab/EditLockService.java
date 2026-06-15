package com.datanote.platform.collab;

import com.datanote.platform.iam.CurrentUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用资源独占编辑锁服务 —— 基于 Redis(原生 TTL 自动过期 + 原子 SETNX)。
 * acquire: SET NX EX 抢锁; 已持有(本人)续约; 他人持有返 holder。心跳续 TTL, 超时(无心跳)自动释放。
 * Redis 不可用时 fail-open(降级可编辑), 由各资源的乐观版本校验兜底防丢更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditLockService {

    private final StringRedisTemplate redis;

    /** 锁 TTL(秒): 无心跳超过此值自动释放。前端心跳间隔应明显小于此值(建议 30s)。 */
    public static final long TTL_SECONDS = 90;

    private static final String PREFIX = "editlock:";

    // 仅持有者可续约: get==me 则 pexpire
    private static final DefaultRedisScript<Long> HEARTBEAT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end", Long.class);
    // 仅持有者可释放: get==me 则 del
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private String key(String type, String id) { return PREFIX + type + ":" + id; }

    /** 尝试获取编辑锁。返回 {ok, holder, self}。Redis 异常→降级 ok:true。 */
    public Map<String, Object> acquire(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        Map<String, Object> r = new HashMap<>();
        String k = key(type, id);
        try {
            Boolean got = redis.opsForValue().setIfAbsent(k, me, Duration.ofSeconds(TTL_SECONDS));
            if (Boolean.TRUE.equals(got)) { r.put("ok", true); r.put("self", true); return r; }
            String cur = redis.opsForValue().get(k);
            if (cur == null) {   // 刚好过期, 再抢一次
                got = redis.opsForValue().setIfAbsent(k, me, Duration.ofSeconds(TTL_SECONDS));
                if (Boolean.TRUE.equals(got)) { r.put("ok", true); r.put("self", true); return r; }
                cur = redis.opsForValue().get(k);
            }
            if (me != null && me.equals(cur)) {   // 本人持有 → 续约(可重入)
                redis.expire(k, Duration.ofSeconds(TTL_SECONDS));
                r.put("ok", true); r.put("self", true); return r;
            }
            r.put("ok", false); r.put("holder", cur);
            return r;
        } catch (Exception e) {
            log.warn("编辑锁 acquire 异常(降级可编辑) {}:{}: {}", type, id, e.getMessage());
            r.put("ok", true); r.put("degraded", true);
            return r;
        }
    }

    /** 心跳续锁(仅持有者)。返回是否仍持有。Redis 异常→true(不打断编辑)。 */
    public boolean heartbeat(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        if (me == null) return false;
        try {
            Long n = redis.execute(HEARTBEAT, Collections.singletonList(key(type, id)), me, String.valueOf(TTL_SECONDS * 1000));
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("编辑锁 heartbeat 异常 {}:{}: {}", type, id, e.getMessage());
            return true;
        }
    }

    /** 释放编辑锁(仅持有者)。 */
    public void release(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        if (me == null) return;
        try { redis.execute(RELEASE, Collections.singletonList(key(type, id)), me); }
        catch (Exception e) { log.warn("编辑锁 release 异常 {}:{}: {}", type, id, e.getMessage()); }
    }

    /** 当前持有者(无/已过期返回 null)。Redis 异常→null(fail-open)。 */
    public String currentHolder(String type, String id) {
        try { return redis.opsForValue().get(key(type, id)); }
        catch (Exception e) { return null; }
    }

    /** 保存前断言: 当前用户必须持锁(他人持锁则拒)。供写操作服务端兜底。 */
    public void assertHeld(String type, String id) {
        String me = CurrentUserUtil.currentUser();
        String holder = currentHolder(type, id);
        if (holder != null && me != null && !holder.equals(me)) {
            throw new com.datanote.common.exception.BusinessException("「" + holder + "」正在编辑该内容, 你暂时无法保存; 请稍后再试");
        }
    }
}
