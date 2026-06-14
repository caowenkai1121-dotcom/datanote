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

    /** 运行时配置不可变快照: volatile 单引用原子发布, 消除 reloadConfig 与 embed 并发时的跨字段撕裂读(切 provider/换 key+url)。 */
    private static final class EmbConf {
        final String baseUrl, apiKey, model;
        final int dim;
        EmbConf(String b, String k, String m, int d) { baseUrl = b; apiKey = k; model = m; dim = d; }
    }
    private volatile EmbConf conf = new EmbConf("", "", "text-embedding-v3", 1024);

    public EmbeddingService(DnSystemConfigMapper systemConfigMapper, ObjectMapper objectMapper) {
        this.systemConfigMapper = systemConfigMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void reloadConfig() {
        EmbConf c;
        try {
            String apiKey;
            String dbKey = db("ai.embedding-api-key");
            if (dbKey != null && !dbKey.isEmpty()) {
                String dec = CryptoUtil.decryptSafe(dbKey, cryptoKey);
                apiKey = dec != null ? dec : dbKey;
            } else {
                apiKey = envApiKey;
            }
            String dbUrl = db("ai.embedding-base-url");
            String baseUrl = nonEmpty(dbUrl) ? dbUrl : envBaseUrl;
            String dbModel = db("ai.embedding-model");
            String model = nonEmpty(dbModel) ? dbModel : envModel;
            String dbDim = db("ai.embedding-dim");
            int dim = parseInt(dbDim, envDim);
            c = new EmbConf(baseUrl, apiKey, model, dim);
        } catch (Exception e) {
            c = new EmbConf(envBaseUrl, envApiKey, envModel, envDim);
            log.warn("[embedding] 配置加载异常,使用环境默认: {}", e.getMessage());
        }
        this.conf = c; // 单次 volatile 写: 原子发布自洽快照
        log.info("[embedding] loaded: model={}, dim={}, configured={}", c.model, c.dim, isAvailable());
    }

    public boolean isAvailable() {
        EmbConf c = this.conf;
        return nonEmpty(c.apiKey) && nonEmpty(c.baseUrl);
    }

    public int dim() {
        return this.conf.dim;
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
        EmbConf c = this.conf; // 单次 volatile 读, 全程用自洽快照
        try {
            // 单条截断, 防超长文本致整批 422 静默丢点
            List<String> capped = new ArrayList<>(texts.size());
            for (String t : texts) {
                if (t == null) capped.add("");
                else capped.add(t.length() > MAX_INPUT_CHARS ? t.substring(0, MAX_INPUT_CHARS) : t);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", c.model);
            body.put("input", capped);
            String resp = post(c.baseUrl.replaceAll("/+$", "") + "/embeddings",
                    objectMapper.writeValueAsString(body), c.apiKey);
            if (resp == null) return null;
            JsonNode root = objectMapper.readTree(resp);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("[embedding] 响应无 data: {}", logResp(resp));
                return null;
            }
            // 按响应项的 index 字段回填对应槽位, 不依赖 data 数组顺序(规范不保证有序), 防向量错配到不同文本; index 缺失则退化为位置对应
            float[][] slots = new float[texts.size()][];
            int pos = 0;
            for (JsonNode d : data) {
                JsonNode emb = d.get("embedding");
                if (emb == null || !emb.isArray()) { pos++; continue; }
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) v[i] = (float) emb.get(i).asDouble();
                JsonNode idxNode = d.get("index");
                int idx = (idxNode != null && idxNode.isInt()) ? idxNode.asInt() : pos;
                if (idx >= 0 && idx < slots.length) slots[idx] = v;
                pos++;
            }
            List<float[]> out = new ArrayList<>(texts.size());
            for (float[] v : slots) {
                if (v == null) continue;
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("[embedding] embedBatch 失败: {}", e.getMessage());
            return null;
        }
    }

    private String post(String url, String json, String apiKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (apiKey != null) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                log.warn("[embedding] HTTP {} : {}", code, logResp(resp));
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

    /** 日志截断: 防超长响应体刷屏日志(异常/错误响应只取前缀)。 */
    private static final int LOG_RESP_MAX = 200;

    private static String logResp(String resp) {
        return resp.length() > LOG_RESP_MAX ? resp.substring(0, LOG_RESP_MAX) : resp;
    }
}
