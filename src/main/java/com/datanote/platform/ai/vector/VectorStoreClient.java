package com.datanote.platform.ai.vector;

import com.datanote.common.util.CryptoUtil;
import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 向量库 REST 封装(裸 HttpURLConnection, api-key header, 零新依赖)。
 * 降级铁律: @PostConstruct 探活失败静默禁用不阻断启动; 写失败仅 warn, 读失败返 null。
 */
@Slf4j
@Component
public class VectorStoreClient {

    private final ObjectMapper objectMapper;
    private final DnSystemConfigMapper systemConfigMapper;

    // env 兜底种子(DB 未配时使用)
    @Value("${datanote.vector.enabled:false}")
    private boolean envEnabled;
    @Value("${datanote.vector.base-url:}")
    private String envBaseUrl;
    @Value("${datanote.vector.api-key:}")
    private String envApiKey;
    @Value("${datanote.vector.collection:dn_meta}")
    private String envCollection;
    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    // 运行时配置(DB store.vector.* 优先, env 兜底; reloadConfig 热刷)
    private volatile boolean enabled;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String collection;

    private volatile boolean ready = false;
    private volatile long lastProbe = 0L;

    public VectorStoreClient(ObjectMapper objectMapper, DnSystemConfigMapper systemConfigMapper) {
        this.objectMapper = objectMapper;
        this.systemConfigMapper = systemConfigMapper;
    }

    @PostConstruct
    public void init() {
        reloadConfig();
    }

    /** 重载配置(DB store.vector.* 优先, env 兜底)并重新探活。配置页保存后热生效。 */
    public synchronized void reloadConfig() {
        String dbEnabled = db("store.vector.enabled");
        this.enabled = nonEmpty(dbEnabled) ? "true".equalsIgnoreCase(dbEnabled.trim()) : envEnabled;
        String dbUrl = db("store.vector.base-url");
        this.baseUrl = nonEmpty(dbUrl) ? dbUrl.trim() : envBaseUrl;
        String dbKeyEnc = db("store.vector.api-key");
        if (nonEmpty(dbKeyEnc)) {
            String dec = CryptoUtil.decryptSafe(dbKeyEnc, cryptoKey);
            this.apiKey = dec != null ? dec : dbKeyEnc;
        } else {
            this.apiKey = envApiKey;
        }
        String dbColl = db("store.vector.collection");
        this.collection = nonEmpty(dbColl) ? dbColl.trim() : (nonEmpty(envCollection) ? envCollection : "dn_meta");
        // 重新探活
        this.ready = false;
        this.lastProbe = 0L;
        if (!enabled || baseUrl == null || baseUrl.trim().isEmpty()) {
            log.info("[vector] 向量库未启用(enabled=false 或 url 空)");
            return;
        }
        try {
            if (http("GET", "/collections", null) != null) {
                ready = true;
                log.info("[vector] 向量库已就绪: {} (collection={})", baseUrl, collection);
            } else {
                log.warn("[vector] 探活无响应, 降级禁用");
            }
        } catch (Exception e) {
            log.warn("[vector] 探活失败, 降级禁用: {}", e.getMessage());
        }
    }

    private String db(String key) {
        try {
            DnSystemConfig c = systemConfigMapper.selectById(key);
            return c == null ? null : c.getConfigValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public boolean available() {
        if (!enabled) return false;
        if (ready) return true;
        // 启动探活可能因瞬时网络失败 → 惰性重探(30s 退避), 自愈瞬时抖动而不阻断启动
        long now = System.currentTimeMillis();
        if (now - lastProbe < 30000) return false;
        synchronized (this) {
            if (ready) return true;
            if (System.currentTimeMillis() - lastProbe < 30000) return false;
            lastProbe = System.currentTimeMillis();
            try {
                if (http("GET", "/collections", null) != null) {
                    ready = true;
                    log.info("[vector] 惰性重探成功, 启用: {}", baseUrl);
                }
            } catch (Exception ignore) {
            }
        }
        return ready;
    }

    public String collection() {
        return collection;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 测试给定连接(不改运行态): GET {baseUrl}/collections。配置页"测连接"用。 */
    public boolean testConnection(String testUrl, String testKey) {
        if (testUrl == null || testUrl.trim().isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(testUrl.replaceAll("/+$", "") + "/collections").openConnection();
            conn.setRequestMethod("GET");
            if (testKey != null && !testKey.isEmpty()) conn.setRequestProperty("api-key", testKey);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 幂等建集合(已存在则跳过)。返回是否就绪。 */
    public boolean ensureCollection(int dim) {
        if (!available() || dim <= 0) return false;
        String exists = http("GET", "/collections/" + collection, null);
        if (exists != null) {
            // 集合已存在: 校验已存维度与当前 dim 一致, 不一致则 fail-closed(否则 upsert/search 会被 Qdrant 静默拒绝, 检索悄悄退化)
            Integer existDim = parseCollectionDim(exists);
            if (existDim != null && existDim != dim) {
                log.error("[vector] 集合 {} 已存在但维度不一致(已存={}, 当前={}), 拒绝复用; 请重建集合或重导后再用", collection, existDim, dim);
                return false;
            }
            return true;
        }
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", dim);
        vectors.put("distance", "Cosine");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectors);
        String r = http("PUT", "/collections/" + collection, toJson(body));
        if (r != null) {
            log.info("[vector] 建集合成功: {} dim={}", collection, dim);
            return true;
        }
        return false;
    }

    /** 批量 upsert。points 每项 {id, vector(float[]/List), payload(Map)}。 */
    public boolean upsert(List<Map<String, Object>> points) {
        if (!available() || points == null || points.isEmpty()) return false;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points);
        String r = http("PUT", "/collections/" + collection + "/points?wait=true", toJson(body));
        return r != null;
    }

    /** 按 payload 字段等值删点(如文档删除级联清其所有向量块)。不可用/失败返 false。 */
    public boolean deleteByFilter(String key, Object value) {
        if (!available() || key == null || value == null) return false;
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("value", value);
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("key", key);
        cond.put("match", match);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", java.util.Collections.singletonList(cond));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", filter);
        String r = http("POST", "/collections/" + collection + "/points/delete?wait=true", toJson(body));
        return r != null;
    }

    /** 向量检索 + 可选 kind 过滤。返回 [{id,score,payload}]; 失败返 null。 */
    public List<Map<String, Object>> search(float[] vector, String kind, int limit) {
        if (!available() || vector == null || vector.length == 0) return null;
        Map<String, Object> body = new LinkedHashMap<>();
        List<Float> v = new ArrayList<>(vector.length);
        for (float f : vector) v.add(f);
        body.put("vector", v);
        body.put("limit", limit <= 0 ? 10 : limit);
        body.put("with_payload", true);
        if (kind != null && !kind.trim().isEmpty()) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("value", kind.trim());
            Map<String, Object> cond = new LinkedHashMap<>();
            cond.put("key", "kind");
            cond.put("match", match);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("must", java.util.Collections.singletonList(cond));
            body.put("filter", filter);
        }
        String resp = http("POST", "/collections/" + collection + "/points/search", toJson(body));
        if (resp == null) return null;
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode result = root.get("result");
            List<Map<String, Object>> out = new ArrayList<>();
            if (result == null || !result.isArray()) return out;
            for (JsonNode item : result) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", objectMapper.convertValue(item.get("id"), Object.class));
                m.put("score", item.path("score").asDouble());
                m.put("payload", objectMapper.convertValue(item.get("payload"), Object.class));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("[vector] search 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 从 GET /collections/{name} 响应解析已存集合的向量维度(result.config.params.vectors.size); 解析失败返 null。 */
    private Integer parseCollectionDim(String resp) {
        try {
            JsonNode size = objectMapper.readTree(resp)
                    .path("result").path("config").path("params").path("vectors").path("size");
            return size.isInt() || size.isNumber() ? size.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String http(String method, String path, String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl.replaceAll("/+$", "") + path).openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("api-key", apiKey);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            if (json != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = null;
            if (is != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                resp = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            if (code < 200 || code >= 300) {
                // 404 等用于 ensureCollection 判存在, 静默返 null
                return null;
            }
            return resp == null ? "" : resp;
        } catch (Exception e) {
            log.warn("[vector] HTTP {} {} 异常: {}", method, path, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
