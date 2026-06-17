package com.datanote.platform.ai.graph;

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
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j HTTP Query API 封装（/db/neo4j/tx/commit, Basic Auth, 裸 HttpURLConnection, 零新依赖）。
 * 降级铁律：@PostConstruct 探活失败静默禁用绝不阻断启动；读写失败仅 warn 返 null，绝不进 @Transactional。
 */
@Slf4j
@Component
public class GraphStoreClient {

    /** 启动探活瞬时失败后惰性重探的退避间隔（毫秒）。 */
    private static final long PROBE_BACKOFF_MS = 30000L;
    /** 保证 Table.fqn 唯一的约束（幂等创建，MERGE 无重复节点）。 */
    private static final String CONSTRAINT_TABLE_FQN =
            "CREATE CONSTRAINT dn_table_fqn IF NOT EXISTS FOR (t:Table) REQUIRE t.fqn IS UNIQUE";

    private final ObjectMapper objectMapper;
    private final DnSystemConfigMapper systemConfigMapper;

    // env 兜底种子(DB 未配时使用)
    @Value("${datanote.graph.enabled:false}")
    private boolean envEnabled;
    @Value("${datanote.graph.base-url:}")
    private String envBaseUrl;
    @Value("${datanote.graph.user:neo4j}")
    private String envUser;
    @Value("${datanote.graph.password:}")
    private String envPassword;
    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    // 运行时配置(DB store.graph.* 优先, env 兜底; reloadConfig 热刷)
    private volatile boolean enabled;
    private volatile String baseUrl;
    private volatile String user;
    private volatile String password;

    private volatile boolean ready = false;
    private volatile long lastProbe = 0L;

    public GraphStoreClient(ObjectMapper objectMapper, DnSystemConfigMapper systemConfigMapper) {
        this.objectMapper = objectMapper;
        this.systemConfigMapper = systemConfigMapper;
    }

    @PostConstruct
    public void init() {
        reloadConfig();
    }

    /** 重载配置(DB store.graph.* 优先, env 兜底)并重新探活+建唯一约束。配置页保存后热生效。 */
    public synchronized void reloadConfig() {
        String dbEnabled = db("store.graph.enabled");
        this.enabled = nonEmpty(dbEnabled) ? "true".equalsIgnoreCase(dbEnabled.trim()) : envEnabled;
        String dbUrl = db("store.graph.base-url");
        this.baseUrl = nonEmpty(dbUrl) ? dbUrl.trim() : envBaseUrl;
        String dbUser = db("store.graph.user");
        this.user = nonEmpty(dbUser) ? dbUser.trim() : envUser;
        String dbPwdEnc = db("store.graph.password");
        if (nonEmpty(dbPwdEnc)) {
            String dec = CryptoUtil.decryptSafe(dbPwdEnc, cryptoKey);
            this.password = dec != null ? dec : dbPwdEnc;
        } else {
            this.password = envPassword;
        }
        this.ready = false;
        this.lastProbe = 0L;
        if (!enabled || baseUrl == null || baseUrl.trim().isEmpty()) {
            log.info("[graph] 图数据库未启用(enabled=false 或 url 空)");
            return;
        }
        try {
            List<Map<String, Object>> r = run("RETURN 1 AS ok", null);
            if (r != null) {
                // 唯一约束(幂等), 保证 fqn 唯一、MERGE 无重复节点
                run(CONSTRAINT_TABLE_FQN, null);
                ready = true;
                log.info("[graph] 图数据库已就绪: {}", baseUrl);
            } else {
                log.warn("[graph] 探活无结果, 降级禁用");
            }
        } catch (Exception e) {
            log.warn("[graph] 探活失败, 降级禁用: {}", e.getMessage());
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
        // 启动探活瞬时失败 → 惰性重探(30s 退避)自愈
        long now = System.currentTimeMillis();
        if (now - lastProbe < PROBE_BACKOFF_MS) return false;
        synchronized (this) {
            if (ready) return true;
            if (System.currentTimeMillis() - lastProbe < PROBE_BACKOFF_MS) return false;
            lastProbe = System.currentTimeMillis();
            try {
                if (run("RETURN 1 AS ok", null) != null) {
                    run(CONSTRAINT_TABLE_FQN, null);
                    ready = true;
                    log.info("[graph] 惰性重探成功, 启用: {}", baseUrl);
                }
            } catch (Exception ignore) {
            }
        }
        return ready;
    }

    /** 执行 Cypher（参数化防注入），返回行列表(每行: 列名→值)；失败/有错误返 null。 */
    public List<Map<String, Object>> run(String cypher, Map<String, Object> params) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return null;
        try {
            Map<String, Object> stmt = new LinkedHashMap<>();
            stmt.put("statement", cypher);
            if (params != null && !params.isEmpty()) stmt.put("parameters", params);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("statements", Collections.singletonList(stmt));

            String resp = post(baseUrl.replaceAll("/+$", "") + "/db/neo4j/tx/commit",
                    objectMapper.writeValueAsString(body));
            if (resp == null) return null;
            JsonNode root = objectMapper.readTree(resp);
            JsonNode errors = root.get("errors");
            if (errors != null && errors.isArray() && errors.size() > 0) {
                log.warn("[graph] Cypher 错误: {}", errors.toString());
                return null;
            }
            JsonNode results = root.get("results");
            List<Map<String, Object>> out = new ArrayList<>();
            if (results == null || !results.isArray() || results.size() == 0) return out;
            JsonNode first = results.get(0);
            JsonNode cols = first.get("columns");
            JsonNode data = first.get("data");
            if (cols == null || data == null) return out;
            for (JsonNode d : data) {
                JsonNode rowArr = d.get("row");
                if (rowArr == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.size() && i < rowArr.size(); i++) {
                    row.put(cols.get(i).asText(), objectMapper.convertValue(rowArr.get(i), Object.class));
                }
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            log.warn("[graph] run 失败: {}", e.getMessage());
            return null;
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 测试给定连接(不改运行态): POST {baseUrl}/db/neo4j/tx/commit RETURN 1。配置页"测连接"用。 */
    public boolean testConnection(String testUrl, String testUser, String testPwd) {
        if (testUrl == null || testUrl.trim().isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(testUrl.replaceAll("/+$", "") + "/db/neo4j/tx/commit").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String cred = Base64.getEncoder().encodeToString(
                    ((testUser == null ? "" : testUser) + ":" + (testPwd == null ? "" : testPwd)).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + cred);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"statements\":[{\"statement\":\"RETURN 1 AS ok\"}]}".getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String post(String url, String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            String cred = Base64.getEncoder().encodeToString(
                    ((user == null ? "" : user) + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + cred);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(10000);
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
                log.warn("[graph] HTTP {} : {}", code, resp.length() > 300 ? resp.substring(0, 300) : resp);
                return null;
            }
            return resp;
        } catch (Exception e) {
            log.warn("[graph] HTTP 异常: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
