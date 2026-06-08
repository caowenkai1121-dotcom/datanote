package com.datanote.platform.ai.vector;

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

    @Value("${datanote.vector.enabled:false}")
    private boolean enabled;
    @Value("${datanote.vector.base-url:}")
    private String baseUrl;
    @Value("${datanote.vector.api-key:}")
    private String apiKey;
    @Value("${datanote.vector.collection:dn_meta}")
    private String collection;

    private volatile boolean ready = false;

    public VectorStoreClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!enabled || baseUrl == null || baseUrl.trim().isEmpty()) {
            log.info("[vector] 向量库未启用(vector.enabled=false)");
            return;
        }
        try {
            String r = http("GET", "/collections", null);
            if (r != null) {
                ready = true;
                log.info("[vector] 向量库已就绪: {} (collection={})", baseUrl, collection);
            } else {
                log.warn("[vector] 探活无响应, 降级禁用");
            }
        } catch (Exception e) {
            log.warn("[vector] 探活失败, 降级禁用: {}", e.getMessage());
        }
    }

    public boolean available() {
        return enabled && ready;
    }

    public String collection() {
        return collection;
    }

    /** 幂等建集合(已存在则跳过)。返回是否就绪。 */
    public boolean ensureCollection(int dim) {
        if (!available() || dim <= 0) return false;
        String exists = http("GET", "/collections/" + collection, null);
        if (exists != null) return true;
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
