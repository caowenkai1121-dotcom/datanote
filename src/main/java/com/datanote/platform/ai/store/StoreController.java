package com.datanote.platform.ai.store;

import com.datanote.common.model.R;
import com.datanote.common.util.CryptoUtil;
import com.datanote.platform.ai.graph.GraphMirrorService;
import com.datanote.platform.ai.graph.GraphStoreClient;
import com.datanote.platform.ai.vector.EmbeddingService;
import com.datanote.platform.ai.vector.SemanticSearchService;
import com.datanote.platform.ai.vector.VectorIndexService;
import com.datanote.platform.ai.vector.VectorStoreClient;
import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 向量库/图库 健康检查与手动同步(运维 + 降级自检)。 */
@RestController
@RequestMapping("/api/ai/store")
@RequiredArgsConstructor
public class StoreController {

    private final VectorStoreClient vector;
    private final GraphStoreClient graph;
    private final EmbeddingService embedding;
    private final GraphMirrorService graphMirror;
    private final VectorIndexService vectorIndex;
    private final SemanticSearchService semanticSearch;
    private final DnSystemConfigMapper systemConfigMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;
    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;
    @Value("${spring.redis.port:6379}")
    private String redisPort;

    /** 健康检查: 向量库/图库/嵌入 三态。 */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ready", vector.available());
        v.put("collection", vector.collection());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("ready", graph.available());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("available", embedding.isAvailable());
        e.put("dim", embedding.dim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector", v);
        out.put("graph", g);
        out.put("embedding", e);
        return R.ok(out);
    }

    /** 语义检索(向量库,降级关键字): q=检索词, kind=table/column/glossary/metric(可选), limit(默认10)。 */
    @GetMapping("/search")
    public R<Map<String, Object>> search(@RequestParam("q") String q,
                                         @RequestParam(value = "kind", required = false) String kind,
                                         @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        return R.ok(semanticSearch.search(q, kind, limit));
    }

    /** 手动全量同步: 血缘→图库 + 元数据→向量库。各自降级不可用则返 0。 */
    @PostMapping("/sync")
    public R<Map<String, Object>> sync() {
        int g = graphMirror.fullSync();
        int v = vectorIndex.fullReindex();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphEdges", g);
        out.put("vectorPoints", v);
        return R.ok(out);
    }

    /** 列级语义索引重建(异步: 列量大, 后台串行跑, 立即返回; 进度查 /sync-columns/status)。 */
    @PostMapping("/sync-columns")
    public R<Map<String, Object>> syncColumns() {
        Map<String, Object> st = vectorIndex.columnIndexStatus();
        if (Boolean.TRUE.equals(st.get("indexing"))) {
            st.put("started", false);
            st.put("note", "列级重建已在进行中");
            return R.ok(st);
        }
        vectorIndex.reindexColumnsAsync();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        out.put("note", "列级重建已在后台启动, 进度查 /api/ai/store/sync-columns/status");
        return R.ok(out);
    }

    /** 列级索引进度。 */
    @GetMapping("/sync-columns/status")
    public R<Map<String, Object>> syncColumnsStatus() {
        return R.ok(vectorIndex.columnIndexStatus());
    }

    /** 读 嵌入/向量库/图库 配置(密钥脱敏)+ 运行态。 */
    @GetMapping("/config")
    public R<Map<String, Object>> getStoreConfig() {
        Map<String, Object> emb = new LinkedHashMap<>();
        emb.put("provider", cfg("ai.embedding-provider", "bailian"));
        emb.put("baseUrl", cfg("ai.embedding-base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        emb.put("model", cfg("ai.embedding-model", "text-embedding-v3"));
        emb.put("dim", cfg("ai.embedding-dim", "1024"));
        emb.put("apiKeyMasked", mask(cfgRaw("ai.embedding-api-key")));
        emb.put("available", embedding.isAvailable());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ready", vector.available());
        v.put("collection", vector.collection());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("ready", graph.available());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("embedding", emb);
        out.put("vector", v);
        out.put("graph", g);
        return R.ok(out);
    }

    /** 保存 嵌入 配置(密钥 AES 加密存)+ 热重载 EmbeddingService。普通对话(chat)配置仍在 /api/ai/config(可用 DeepSeek)。 */
    @PostMapping("/config")
    public R<Void> saveStoreConfig(@RequestBody Map<String, Object> body) {
        if (body == null) return R.fail("参数为空");
        saveCfg("ai.embedding-provider", str(body.get("provider")), "嵌入服务 provider");
        saveCfg("ai.embedding-base-url", str(body.get("baseUrl")), "嵌入服务 base-url");
        saveCfg("ai.embedding-model", str(body.get("model")), "嵌入模型");
        saveCfg("ai.embedding-dim", str(body.get("dim")), "嵌入维度");
        String apiKey = str(body.get("apiKey"));
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.contains("***")) {
            String enc = CryptoUtil.encrypt(apiKey, cryptoKey);
            saveCfg("ai.embedding-api-key", enc != null ? enc : apiKey, "嵌入服务 API Key(加密)");
        }
        embedding.reloadConfig();   // 热生效
        return R.ok();
    }

    /** 三库(向量/图/Redis)连接配置(密钥脱敏)+ 运行态。 */
    @GetMapping("/db-config")
    public R<Map<String, Object>> getDbConfig() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("enabled", vector.enabled());
        v.put("baseUrl", cfg("store.vector.base-url", vector.baseUrl()));
        v.put("collection", vector.collection());
        v.put("apiKeyMasked", mask(cfgRaw("store.vector.api-key")));
        v.put("ready", vector.available());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("enabled", graph.enabled());
        g.put("baseUrl", cfg("store.graph.base-url", graph.baseUrl()));
        g.put("user", cfg("store.graph.user", "neo4j"));
        g.put("passwordMasked", mask(cfgRaw("store.graph.password")));
        g.put("ready", graph.available());
        Map<String, Object> rd = new LinkedHashMap<>();
        rd.put("host", cfg("store.redis.host", redisHost));
        rd.put("port", cfg("store.redis.port", redisPort));
        rd.put("passwordMasked", mask(cfgRaw("store.redis.password")));
        rd.put("ready", redisPing());
        rd.put("note", "Redis 连接在服务启动时装配, 修改后需重启服务生效");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector", v);
        out.put("graph", g);
        out.put("redis", rd);
        return R.ok(out);
    }

    /** 保存三库连接配置: 向量/图热生效, Redis 存配置(需重启)。密钥 AES 加密存。 */
    @PostMapping("/db-config")
    public R<Map<String, Object>> saveDbConfig(@RequestBody Map<String, Object> body) {
        if (body == null) return R.fail("参数为空");
        Map<String, Object> vec = asMap(body.get("vector"));
        if (vec != null) {
            if (vec.get("enabled") != null) saveCfg("store.vector.enabled", String.valueOf(toBool(vec.get("enabled"))), "向量库启用");
            saveCfg("store.vector.base-url", str(vec.get("baseUrl")), "向量库 base-url");
            saveCfg("store.vector.collection", str(vec.get("collection")), "向量库 collection");
            saveSecret("store.vector.api-key", str(vec.get("apiKey")), "向量库 api-key(加密)");
        }
        Map<String, Object> gr = asMap(body.get("graph"));
        if (gr != null) {
            if (gr.get("enabled") != null) saveCfg("store.graph.enabled", String.valueOf(toBool(gr.get("enabled"))), "图库启用");
            saveCfg("store.graph.base-url", str(gr.get("baseUrl")), "图库 base-url");
            saveCfg("store.graph.user", str(gr.get("user")), "图库 user");
            saveSecret("store.graph.password", str(gr.get("password")), "图库 password(加密)");
        }
        Map<String, Object> rd = asMap(body.get("redis"));
        boolean redisChanged = false;
        if (rd != null) {
            if (rd.get("host") != null) { saveCfg("store.redis.host", str(rd.get("host")), "Redis host"); redisChanged = true; }
            if (rd.get("port") != null) { saveCfg("store.redis.port", str(rd.get("port")), "Redis port"); redisChanged = true; }
            String p = str(rd.get("password"));
            if (p != null && !p.isEmpty() && !p.contains("***")) { saveSecret("store.redis.password", p, "Redis password(加密)"); redisChanged = true; }
        }
        vector.reloadConfig();  // 向量/图热生效
        graph.reloadConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vectorReady", vector.available());
        out.put("graphReady", graph.available());
        out.put("redisNeedRestart", redisChanged);
        return R.ok(out);
    }

    /** 逐库测连接(优先用提交的值测, 不改运行态; 未提供则测当前已存配置)。 */
    @PostMapping("/test")
    public R<Map<String, Object>> testConn(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> vec = body == null ? null : asMap(body.get("vector"));
        String vUrl = (vec != null && str(vec.get("baseUrl")) != null) ? str(vec.get("baseUrl")) : cfg("store.vector.base-url", vector.baseUrl());
        String vKey = vec != null ? str(vec.get("apiKey")) : null;
        if (vKey == null || vKey.isEmpty() || vKey.contains("***")) vKey = decryptCfg("store.vector.api-key");
        out.put("vector", vector.testConnection(vUrl, vKey));
        Map<String, Object> gr = body == null ? null : asMap(body.get("graph"));
        String gUrl = (gr != null && str(gr.get("baseUrl")) != null) ? str(gr.get("baseUrl")) : cfg("store.graph.base-url", graph.baseUrl());
        String gUser = (gr != null && str(gr.get("user")) != null) ? str(gr.get("user")) : cfg("store.graph.user", "neo4j");
        String gPwd = gr != null ? str(gr.get("password")) : null;
        if (gPwd == null || gPwd.isEmpty() || gPwd.contains("***")) gPwd = decryptCfg("store.graph.password");
        out.put("graph", graph.testConnection(gUrl, gUser, gPwd));
        out.put("redis", redisPing());
        return R.ok(out);
    }

    /** 加密保存密钥(空或含*** 占位则跳过, 不覆盖已存)。 */
    private void saveSecret(String key, String plain, String desc) {
        if (plain == null || plain.isEmpty() || plain.contains("***")) return;
        String enc = CryptoUtil.encrypt(plain, cryptoKey);
        saveCfg(key, enc != null ? enc : plain, desc);
    }

    private String decryptCfg(String key) {
        String enc = cfgRaw(key);
        if (enc == null || enc.isEmpty()) return null;
        String d = CryptoUtil.decryptSafe(enc, cryptoKey);
        return d != null ? d : enc;
    }

    private boolean redisPing() {
        org.springframework.data.redis.connection.RedisConnection c = null;
        try {
            c = redisTemplate.getRequiredConnectionFactory().getConnection();
            c.ping();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s);
    }

    private String cfg(String key, String def) {
        String v = cfgRaw(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private String cfgRaw(String key) {
        try {
            DnSystemConfig c = systemConfigMapper.selectById(key);
            return c == null ? null : c.getConfigValue();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCfg(String key, String value, String desc) {
        if (value == null) return;
        DnSystemConfig existing = systemConfigMapper.selectById(key);
        DnSystemConfig c = new DnSystemConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setDescription(desc);
        c.setUpdatedAt(LocalDateTime.now());
        if (existing != null) systemConfigMapper.updateById(c);
        else systemConfigMapper.insert(c);
    }

    private static String mask(String enc) {
        if (enc == null || enc.isEmpty()) return "";
        return "已配置(加密)";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
