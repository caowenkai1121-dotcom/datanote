package com.datanote.platform.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.config.mapper.DnSystemConfigMapper;
import com.datanote.platform.config.model.DnSystemConfig;
import com.datanote.common.util.CryptoUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * AI 辅助开发服务 — 调用 Claude API 实现 NL2SQL、SQL 解释等智能功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistService {

    private final ObjectMapper objectMapper;
    private final DnSystemConfigMapper systemConfigMapper;

    @Value("${datanote.ai.api-key:}")
    private String envApiKey;

    @Value("${datanote.ai.model:claude-sonnet-4-6}")
    private String envModel;

    @Value("${datanote.ai.base-url:https://api.anthropic.com}")
    private String envBaseUrl;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    /** 运行时配置不可变快照: volatile 单引用原子发布, 消除并发可见性 + 跨字段撕裂读(被主/子代理/cron/learn 多线程并发读 chat) */
    private static final class AiConf {
        final String apiKey, model, baseUrl, provider;
        AiConf(String k, String m, String b, String p) { apiKey = k; model = m; baseUrl = b; provider = p; }
    }
    private volatile AiConf conf = new AiConf("", "claude-sonnet-4-6", "https://api.anthropic.com", "anthropic");

    /** 单次回复输出上限: 4096 偏小, 多层建模(ODS→DWD→DWS→ADS)等长答复会被截断; 8192 对 Anthropic/DeepSeek/百炼均安全 */
    private static final int MAX_TOKENS = 8192;

    /** 每请求模型热切覆盖(同 provider 下换 model 档位): 由 Controller 在请求边界 set/clear, chat 读取优先于快照。 */
    private static final ThreadLocal<String> MODEL_OVERRIDE = new ThreadLocal<>();
    public static void setModelOverride(String m) { if (m != null && !m.trim().isEmpty()) MODEL_OVERRIDE.set(m.trim()); }
    public static void clearModelOverride() { MODEL_OVERRIDE.remove(); }
    /** 读当前线程模型档位(供子代理在新线程上继承父的档位, 让⚡快速对子代理也生效) */
    public static String getModelOverride() { return MODEL_OVERRIDE.get(); }

    @PostConstruct
    public void reloadConfig() {
        String dbKey = getDbConfig("ai.api-key");
        String key;
        if (dbKey != null && !dbKey.isEmpty()) {
            String decrypted = CryptoUtil.decryptSafe(dbKey, cryptoKey); // Safe:解密失败回退原值,避免@PostConstruct抛异常致启动崩溃
            key = decrypted != null ? decrypted : dbKey;
        } else {
            key = envApiKey;
        }
        String dbModel = getDbConfig("ai.model");
        String m = (dbModel != null && !dbModel.isEmpty()) ? dbModel : envModel;
        String dbUrl = getDbConfig("ai.base-url");
        String b = (dbUrl != null && !dbUrl.isEmpty()) ? dbUrl : envBaseUrl;
        String dbProvider = getDbConfig("ai.provider");
        String p = (dbProvider != null && !dbProvider.isEmpty()) ? dbProvider : "anthropic";
        this.conf = new AiConf(key, m, b, p); // 单次 volatile 写: 原子发布自洽快照
        log.info("AI config loaded: provider={}, model={}, baseUrl={}, keyConfigured={}", p, m, b, key != null && !key.isEmpty());
    }

    /**
     * 判断是否使用 OpenAI 兼容格式（百炼/OpenAI/DeepSeek 都走这个格式）
     */
    private boolean isOpenAiCompatible(String p) {
        return "openai".equals(p) || "deepseek".equals(p) || "bailian".equals(p) || "custom".equals(p);
    }

    private String getDbConfig(String key) {
        try {
            DnSystemConfig cfg = systemConfigMapper.selectById(key);
            return cfg != null ? cfg.getConfigValue() : null;
        } catch (Exception e) {
            // 表不存在/启动期 DB 未就绪等情况：降级为环境变量兜底，记一笔便于排障（不静默吞错）
            log.warn("读取 AI 系统配置失败,降级使用环境变量兜底: key={}, cause={}", key, e.getMessage());
            return null;
        }
    }

    private static final String SYSTEM_PROMPT =
            "你是一个专业的数据工程师 AI 助手，专注于 SQL 开发和数据分析。\n" +
            "你的职责：\n" +
            "1. 将自然语言需求转换为准确的 SQL 语句\n" +
            "2. 解释复杂的 SQL 语句含义\n" +
            "3. 优化 SQL 性能\n" +
            "4. 回答数据工程相关问题\n\n" +
            "规则：\n" +
            "- 默认使用 DorisSQL 语法（支持 Doris OLAP 表、Duplicate Key 等）\n" +
            "- SQL 语句用 ```sql 代码块包裹\n" +
            "- 回答简洁专业，中文回复";

    /**
     * 调用 Claude API 进行对话
     *
     * @param userMessage 用户消息
     * @param context     上下文信息（如表结构、历史对话等）
     * @return AI 回复文本
     */
    public String chat(String userMessage, String context) {
        AiConf c = this.conf; // 单次 volatile 读, 全程用自洽快照
        if (c.apiKey == null || c.apiKey.isEmpty()) {
            return "AI 功能未配置。请在【系统配置 → AI 配置】中设置 API Key。";
        }
        if (userMessage == null || userMessage.trim().isEmpty()) {
            // 保持降级语义(不抛异常,调用方直接 trim 结果),仅给出明确提示
            return "AI 请求失败: 用户消息不能为空";
        }

        try {
            String fullMessage = userMessage;
            if (context != null && !context.isEmpty()) {
                fullMessage = "当前上下文：\n" + context + "\n\n用户问题：" + userMessage;
            }

            String override = MODEL_OVERRIDE.get();
            String activeModel = override != null ? override : c.model; // 会话级热切优先(读一次, 避免重复 ThreadLocal 取值)
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", activeModel);

            List<Map<String, String>> messages = new ArrayList<>();
            if (isOpenAiCompatible(c.provider)) {
                // OpenAI 兼容格式（百炼/OpenAI/DeepSeek）：system 作为 message
                requestBody.put("max_tokens", MAX_TOKENS);
                Map<String, String> sysMsg = new HashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", SYSTEM_PROMPT);
                messages.add(sysMsg);
            } else {
                // Anthropic 格式：system 是顶层字段
                requestBody.put("max_tokens", MAX_TOKENS);
                requestBody.put("system", SYSTEM_PROMPT);
            }
            Map<String, String> msg = new HashMap<>();
            msg.put("role", "user");
            msg.put("content", fullMessage);
            messages.add(msg);
            requestBody.put("messages", messages);

            // 近窗口告警: 输入过大时提示(配合 ContextCompressor; 防默默超窗失败)
            if (fullMessage.length() > 160000) log.warn("[llm] 输入较大 {} 字符, 接近上下文窗口, 建议压缩 trace", fullMessage.length());
            long _t0 = System.currentTimeMillis();
            String responseBody = callApi(objectMapper.writeValueAsString(requestBody), c.provider, c.apiKey, c.baseUrl);
            // 可观测性: 记录 LLM 调用耗时/模型/输入规模, 便于排查慢调用与成本
            if (log.isInfoEnabled()) log.info("[llm] model={} inChars={} latency={}ms", activeModel, fullMessage.length(), System.currentTimeMillis() - _t0);
            if (responseBody == null || responseBody.isEmpty()) {
                log.error("AI API 返回空响应: provider={}, model={}", c.provider, activeModel);
                return "AI 返回格式异常";
            }
            JsonNode root = objectMapper.readTree(responseBody);

            // 成本可观测: 记录 token 用量(Anthropic input/output_tokens; OpenAI prompt/completion_tokens)
            JsonNode usage = root.get("usage");
            if (usage != null && log.isInfoEnabled()) {
                int in = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : (usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : -1);
                int out = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : (usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : -1);
                log.info("[llm] tokens in={} out={}", in, out);
            }

            // Anthropic 格式响应
            JsonNode contentArr = root.get("content");
            if (contentArr != null && contentArr.isArray() && contentArr.size() > 0) {
                JsonNode textNode = contentArr.get(0).get("text");
                if (textNode != null) {
                    return textNode.asText();
                }
            }
            // OpenAI 兼容格式响应
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                JsonNode contentNode = message != null ? message.get("content") : null;
                if (contentNode != null) {
                    return contentNode.asText();
                }
            }
            // 错误处理
            JsonNode errorNode = root.get("error");
            if (errorNode != null) {
                JsonNode errMsgNode = errorNode.get("message");
                String errorMsg = errMsgNode != null ? errMsgNode.asText() : errorNode.toString();
                log.error("AI API 错误: provider={}, msg={}", c.provider, errorMsg);
                return "AI 请求失败: " + errorMsg;
            }
            log.warn("AI 返回格式异常,无法解析响应: provider={}, body(截断)={}", c.provider,
                    responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
            return "AI 返回格式异常";
        } catch (Exception e) {
            log.error("AI 助手调用异常", e);
            return "AI 请求失败: " + e.getMessage();
        }
    }

    /**
     * 流式对话(SSE 用): 逐增量回调 onToken, 返回累计全文。不动 chat() 签名(红线)。
     * 降级铁律: 流式失败且未产出任何增量 → 回退非流式 chat() 取全文并整段回调; 已产出部分则返回已累计。
     */
    public String chatStream(String userMessage, String context, Consumer<String> onToken) {
        AiConf c = this.conf; // 单次 volatile 读, 全程用自洽快照
        if (c.apiKey == null || c.apiKey.isEmpty()) {
            String r = "AI 功能未配置。请在【系统配置 → AI 配置】中设置 API Key。";
            emit(onToken, r); return r;
        }
        if (userMessage == null || userMessage.trim().isEmpty()) {
            String r = "AI 请求失败: 用户消息不能为空"; emit(onToken, r); return r;
        }
        StringBuilder acc = new StringBuilder();
        try {
            String fullMessage = userMessage;
            if (context != null && !context.isEmpty()) {
                fullMessage = "当前上下文：\n" + context + "\n\n用户问题：" + userMessage;
            }
            String override = MODEL_OVERRIDE.get();
            String activeModel = override != null ? override : c.model;
            boolean openai = isOpenAiCompatible(c.provider);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", activeModel);
            requestBody.put("max_tokens", MAX_TOKENS);
            requestBody.put("stream", true);
            List<Map<String, String>> messages = new ArrayList<>();
            if (openai) {
                Map<String, String> sysMsg = new HashMap<>();
                sysMsg.put("role", "system"); sysMsg.put("content", SYSTEM_PROMPT);
                messages.add(sysMsg);
            } else {
                requestBody.put("system", SYSTEM_PROMPT);
            }
            Map<String, String> msg = new HashMap<>();
            msg.put("role", "user"); msg.put("content", fullMessage);
            messages.add(msg);
            requestBody.put("messages", messages);
            callApiStream(objectMapper.writeValueAsString(requestBody), openai, c.apiKey, c.baseUrl,
                    delta -> { acc.append(delta); emit(onToken, delta); });
        } catch (Exception e) {
            log.warn("AI 流式调用异常, 回退非流式: {}", e.getMessage());
        }
        if (acc.length() > 0) return acc.toString();
        // 未产出任何增量 → 回退非流式取全文, 整段回调一次
        String full = chat(userMessage, context);
        emit(onToken, full);
        return full;
    }

    private static void emit(Consumer<String> onToken, String s) {
        if (onToken == null || s == null || s.isEmpty()) return;
        try { onToken.accept(s); } catch (Exception ignore) {}
    }

    /** 读 SSE 流, 解析 data: 行增量(OpenAI choices[].delta.content / Anthropic content_block_delta.delta.text), 回调 onDelta。 */
    private void callApiStream(String body, boolean openai, String key, String base, Consumer<String> onDelta) throws Exception {
        String endpoint = openai ? "/v1/chat/completions" : "/v1/messages";
        HttpURLConnection conn = (HttpURLConnection) new URL(base + endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        if (openai) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        } else {
            conn.setRequestProperty("x-api-key", key);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            java.io.InputStream es = conn.getErrorStream();
            String err = "";
            if (es != null) { err = new String(readAll(es), StandardCharsets.UTF_8); es.close(); }
            conn.disconnect();
            throw new RuntimeException("stream HTTP " + code + " " + (err.length() > 200 ? err.substring(0, 200) : err));
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                try {
                    JsonNode node = objectMapper.readTree(data);
                    String delta = null;
                    if (openai) {
                        JsonNode ch = node.path("choices");
                        if (ch.isArray() && ch.size() > 0) {
                            JsonNode d = ch.get(0).path("delta").path("content");
                            if (d.isTextual()) delta = d.asText();
                        }
                    } else if ("content_block_delta".equals(node.path("type").asText())) {
                        JsonNode t = node.path("delta").path("text");
                        if (t.isTextual()) delta = t.asText();
                    }
                    if (delta != null && !delta.isEmpty()) onDelta.accept(delta);
                } catch (Exception ignore) {
                    // 单行解析失败不中断整流(SSE 心跳/注释行等)
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = is.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /**
     * NL2SQL：自然语言转 SQL
     */
    public String nl2sql(String question, String tableSchema) {
        // null 兜底,避免拼出字面量 "null"(保持原降级语义,空入参由 chat 统一降级)
        String schema = tableSchema != null ? tableSchema : "";
        String context = "以下是可用的表结构信息：\n" + schema;
        String prompt = "请根据以下需求生成 SQL 语句：\n" + question + "\n\n要求：只返回可执行的 SQL，用 ```sql 包裹。";
        return chat(prompt, context);
    }

    /**
     * SQL 解释
     */
    public String explainSql(String sql) {
        String prompt = "请解释以下 SQL 的含义，包括每个部分的作用：\n```sql\n" + sql + "\n```";
        return chat(prompt, null);
    }

    /**
     * SQL 优化建议
     */
    public String optimizeSql(String sql) {
        String prompt = "请分析以下 SQL 的性能问题并给出优化建议：\n```sql\n" + sql + "\n```";
        return chat(prompt, null);
    }

    private String callApi(String body, String prov, String key, String base) throws Exception {
        boolean openai = isOpenAiCompatible(prov);
        String endpoint = openai ? "/v1/chat/completions" : "/v1/messages";
        URL url = new URL(base + endpoint);
        int maxAttempts = 3; // 网关/网络抖动(5xx/超时)线性退避重试; 429限流交上层 callLlmWithRetry 智能退避, 不在此重试(防双层叠加)
        java.io.IOException lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection conn = null;
            java.io.InputStream is = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Content-Type", "application/json");
                if (openai) {
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                } else {
                    conn.setRequestProperty("x-api-key", key);
                    conn.setRequestProperty("anthropic-version", "2023-06-01");
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                byte[] bytes = new byte[0];
                if (is != null) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    bytes = baos.toByteArray();
                }
                // 网关错误(5xx)退避重试; 429限流直接返回交上层指数退避(避免与 callLlmWithRetry 双层叠加致 8-11s); 4xx鉴权/参数直接返回解析
                if ((code == 500 || code == 502 || code == 503 || code == 504) && attempt < maxAttempts) {
                    long wait = 1000L * attempt; // 线性退避 1s, 2s
                    log.warn("[llm] HTTP {} 第{}次, {}ms 后重试", code, attempt, wait);
                    Thread.sleep(wait);
                    continue;
                }
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                lastEx = e; // 含 SocketTimeoutException
                if (attempt < maxAttempts) {
                    log.warn("[llm] 网络异常 第{}次重试: {}", attempt, e.getMessage());
                    Thread.sleep(1000L * attempt);
                    continue;
                }
                throw e;
            } finally {
                if (is != null) try { is.close(); } catch (Exception ignore) {} // 防 read 抛异常时流泄漏
                if (conn != null) conn.disconnect();                           // 每次迭代释放连接, 防泄漏耗尽连接池
            }
        }
        if (lastEx != null) throw lastEx;
        return ""; // 不可达
    }

    /**
     * 检查 AI 功能是否可用
     */
    public boolean isAvailable() {
        AiConf c = this.conf;
        return c.apiKey != null && !c.apiKey.isEmpty();
    }

    /**
     * 测试 AI 连接是否正常
     */
    public boolean testConnection(String prov, String testKey, String testBaseUrl, String testModel) {
        if (testKey == null || testKey.isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            boolean openai = isOpenAiCompatible(prov);
            String base = testBaseUrl != null && !testBaseUrl.isEmpty() ? testBaseUrl : "https://api.anthropic.com";
            String endpoint = openai ? "/v1/chat/completions" : "/v1/messages";
            conn = (HttpURLConnection) new URL(base + endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (openai) {
                conn.setRequestProperty("Authorization", "Bearer " + testKey);
            } else {
                conn.setRequestProperty("x-api-key", testKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            String m = testModel != null && !testModel.isEmpty() ? testModel : "claude-sonnet-4-6";
            // OpenAI 兼容与 Anthropic 测试请求体一致(均 messages 数组), 原 if/else 两分支完全相同, 收为一行
            String body = "{\"model\":\"" + m + "\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            log.warn("AI connection test failed: {}", e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect(); // 释放连接, 防反复测试配置泄漏
        }
    }
}
