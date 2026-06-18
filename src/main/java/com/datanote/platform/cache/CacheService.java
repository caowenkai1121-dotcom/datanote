package com.datanote.platform.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 通用缓存旁路(Redis, JSON 值, fail-open)。热读路径缓存复用单元。
 * 降级铁律: Redis 任何异常一律直接走 loader 取实时值, 绝不因缓存层故障影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 取缓存命中即返; 未命中/不可用 → 调 loader 取值并回填(ttlSec 秒)。Class 版(简单类型/POJO)。 */
    public <T> T getOrLoad(String key, long ttlSec, Class<T> type, Supplier<T> loader) {
        T cached = read(key, type, null);
        if (cached != null) return cached;
        T v = loader.get();
        write(key, v, ttlSec);
        return v;
    }

    /** 取缓存; 泛型容器(Map/List)版, 传 TypeReference。 */
    public <T> T getOrLoad(String key, long ttlSec, TypeReference<T> type, Supplier<T> loader) {
        T cached = read(key, null, type);
        if (cached != null) return cached;
        T v = loader.get();
        write(key, v, ttlSec);
        return v;
    }

    /** 删除单键(写时失效)。 */
    public void evict(String key) {
        try { redis.delete(key); } catch (Exception ignore) {}
    }

    /** 按前缀批量删(模块数据变更时失效该模块所有缓存)。P2-05: 用 SCAN 游标分批删, 避免 KEYS 在大 keyspace 阻塞 Redis。 */
    public void evictPrefix(String prefix) {
        try {
            org.springframework.data.redis.core.ScanOptions opts =
                    org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix + "*").count(500).build();
            java.util.List<String> batch = new java.util.ArrayList<>();
            try (org.springframework.data.redis.core.Cursor<String> cur = redis.scan(opts)) {
                while (cur.hasNext()) {
                    batch.add(cur.next());
                    if (batch.size() >= 500) { redis.delete(batch); batch.clear(); }
                }
            }
            if (!batch.isEmpty()) redis.delete(batch);
        } catch (Exception ignore) {}
    }

    @SuppressWarnings("unchecked")
    private <T> T read(String key, Class<T> type, TypeReference<T> ref) {
        try {
            String s = redis.opsForValue().get(key);
            if (s == null || s.isEmpty()) return null;
            return ref != null ? objectMapper.readValue(s, ref) : objectMapper.readValue(s, type);
        } catch (Exception e) {
            return null;   // 反序列化/连接异常 → 视为未命中, 走 loader
        }
    }

    private void write(String key, Object v, long ttlSec) {
        if (v == null) return;
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(v), ttlSec, TimeUnit.SECONDS);
        } catch (Exception ignore) {
            // 回填失败不影响返回实时值
        }
    }
}
