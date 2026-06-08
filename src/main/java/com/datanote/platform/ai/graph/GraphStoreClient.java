package com.datanote.platform.ai.graph;

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

    private final ObjectMapper objectMapper;

    @Value("${datanote.graph.enabled:false}")
    private boolean enabled;
    @Value("${datanote.graph.base-url:}")
    private String baseUrl;
    @Value("${datanote.graph.user:neo4j}")
    private String user;
    @Value("${datanote.graph.password:}")
    private String password;

    private volatile boolean ready = false;
    private volatile long lastProbe = 0L;

    public GraphStoreClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!enabled || baseUrl == null || baseUrl.trim().isEmpty()) {
            log.info("[graph] 图数据库未启用(graph.enabled=false)");
            return;
        }
        try {
            List<Map<String, Object>> r = run("RETURN 1 AS ok", null);
            if (r != null) {
                // 唯一约束(幂等), 保证 fqn 唯一、MERGE 无重复节点
                run("CREATE CONSTRAINT dn_table_fqn IF NOT EXISTS FOR (t:Table) REQUIRE t.fqn IS UNIQUE", null);
                ready = true;
                log.info("[graph] 图数据库已就绪: {}", baseUrl);
            } else {
                log.warn("[graph] 探活无结果, 降级禁用");
            }
        } catch (Exception e) {
            log.warn("[graph] 探活失败, 降级禁用: {}", e.getMessage());
        }
    }

    public boolean available() {
        if (!enabled) return false;
        if (ready) return true;
        // 启动探活瞬时失败 → 惰性重探(30s 退避)自愈
        long now = System.currentTimeMillis();
        if (now - lastProbe < 30000) return false;
        synchronized (this) {
            if (ready) return true;
            if (System.currentTimeMillis() - lastProbe < 30000) return false;
            lastProbe = System.currentTimeMillis();
            try {
                if (run("RETURN 1 AS ok", null) != null) {
                    run("CREATE CONSTRAINT dn_table_fqn IF NOT EXISTS FOR (t:Table) REQUIRE t.fqn IS UNIQUE", null);
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
