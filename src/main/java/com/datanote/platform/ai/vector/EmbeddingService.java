package com.datanote.platform.ai.vector;

import com.datanote.common.util.CryptoUtil;
import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入(向量化)服务 — OpenAI 兼容 /embeddings(默认百炼 text-embedding-v3 1024维)。
 * 独立于 chat provider(deepseek 无 embeddings 端点)。配置 DB(system_config ai.embedding-*) 优先, env 兜底, key 加密。
 * 未配置/不可用 → isAvailable()=false, 调用方降级(关键字检索/跳过重建), 绝不抛异常拖垮主系统。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final DnSystemConfigMapper systemConfigMapper;
    private final ObjectMapper objectMapper;

    @Value("${datanote.ai.embedding-base-url:}")
    private String envBaseUrl;
    @Value("${datanote.ai.embedding-api-key:}")
    private String envApiKey;
    @Value("${datanote.ai.embedding-model:text-embedding-v3}")
    private String envModel;
    @Value("${datanote.ai.embedding-dim:1024}")
    private int envDim;
    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String model;
    private volatile int dim;

    public EmbeddingService(DnSystemConfigMapper systemConfigMapper, ObjectMapper objectMapper) {
        this.systemConfigMapper = systemConfigMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void reloadConfig() {
        try {
            String dbKey = db("ai.embedding-api-key");
            if (dbKey != null && !dbKey.isEmpty()) {
                String dec = CryptoUtil.decryptSafe(dbKey, cryptoKey);
                this.apiKey = dec != null ? dec : dbKey;
            } else {
                this.apiKey = envApiKey;
            }
            String dbUrl = db("ai.embedding-base-url");
            this.baseUrl = nonEmpty(dbUrl) ? dbUrl : envBaseUrl;
            String dbModel = db("ai.embedding-model");
            this.model = nonEmpty(dbModel) ? dbModel : envModel;
            String dbDim = db("ai.embedding-dim");
            this.dim = parseInt(dbDim, envDim);
        } catch (Exception e) {
            this.apiKey = envApiKey;
            this.baseUrl = envBaseUrl;
            this.model = envModel;
            this.dim = envDim;
            log.warn("[embedding] 配置加载异常,使用环境默认: {}", e.getMessage());
        }
        log.info("[embedding] loaded: model={}, dim={}, configured={}", model, dim, isAvailable());
    }

    public boolean isAvailable() {
        return nonEmpty(apiKey) && nonEmpty(baseUrl);
    }

    public int dim() {
        return dim;
    }

    public float[] embed(String text) {
        List<float[]> r = embedBatch(Collections.singletonList(text));
        return (r == null || r.isEmpty()) ? null : r.get(0);
    }

    /** 单次请求上限(DashScope text-embedding-v3 限 10; 兼容其它 provider 取保守值)。 */
    private static final int MAX_BATCH = 10;
    /** 单条文本字符上限: 防超长 comment/定义撑爆 provider 单条 token 限制致整批失败丢点。 */
    private static final int MAX_INPUT_CHARS = 2000;

    /** 批量嵌入(内部按 MAX_BATCH 分块, 适配 provider 单批上限)；不可用/任一子批失败返 null。 */
    public List<float[]> embedBatch(List<String> texts) {
        if (!isAvailable() || texts == null || texts.isEmpty()) return null;
        List<float[]> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH) {
            List<String> chunk = texts.subList(i, Math.min(i + MAX_BATCH, texts.size()));
            List<float[]> part = embedChunk(chunk);
            if (part == null || part.size() != chunk.size()) return null;
            all.addAll(part);
        }
        return all;
    }

    /** 单批(≤MAX_BATCH)嵌入。 */
    private List<float[]> embedChunk(List<String> texts) {
        try {
            // 单条截断, 防超长文本致整批 422 静默丢点
            List<String> capped = new ArrayList<>(texts.size());
            for (String t : texts) {
                if (t == null) capped.add("");
                else capped.add(t.length() > MAX_INPUT_CHARS ? t.substring(0, MAX_INPUT_CHARS) : t);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", capped);
            String resp = post(baseUrl.replaceAll("/+$", "") + "/embeddings",
                    objectMapper.writeValueAsString(body));
            if (resp == null) return null;
            JsonNode root = objectMapper.readTree(resp);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("[embedding] 响应无 data: {}", resp.length() > 200 ? resp.substring(0, 200) : resp);
                return null;
            }
            List<float[]> out = new ArrayList<>();
            for (JsonNode d : data) {
                JsonNode emb = d.get("embedding");
                if (emb == null || !emb.isArray()) continue;
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) v[i] = (float) emb.get(i).asDouble();
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("[embedding] embedBatch 失败: {}", e.getMessage());
            return null;
        }
    }

    private String post(String url, String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            String resp = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                log.warn("[embedding] HTTP {} : {}", code, resp.length() > 200 ? resp.substring(0, 200) : resp);
                return null;
            }
            return resp;
        } catch (Exception e) {
            log.warn("[embedding] HTTP 异常: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
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

    private static int parseInt(String s, int def) {
        if (s == null || s.trim().isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
