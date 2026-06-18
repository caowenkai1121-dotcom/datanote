package com.datanote.platform.ai.agent.engine;

import com.datanote.common.util.SecretRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 文本工具（纯函数，可单测）。
 * sanitize: 剥 NUL/控制字符（所有入库/回前端文本必过，保 NUL 字节铁律）。
 * parseToolCalls: 容错抠 &lt;tool_call&gt;{json}&lt;/tool_call&gt;。
 * extractJson: 复用 extractSqlBlock 同款容错（剥 ``` 围栏 + 平衡大括号抠首个 JSON 对象）。
 */
public final class AgentTextUtil {

    private AgentTextUtil() {}

    private static final Pattern TOOL_CALL =
            Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern THINK =
            Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 抠出首个 &lt;think&gt; 内的简短推理(已 sanitize)；无则 null。借鉴 hermes scratchpad/think 分离, 过程留痕不入终答。 */
    public static String extractThink(String text) {
        if (text == null) return null;
        Matcher m = THINK.matcher(text);
        if (m.find()) {
            String t = sanitize(m.group(1)).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    /** 从文本剥除所有 &lt;think&gt;…&lt;/think&gt; 块(终答呈现给用户前调用)。null → null。 */
    public static String stripThink(String text) {
        if (text == null) return null;
        return THINK.matcher(text).replaceAll("").trim();
    }

    // 敏感键值: password=xxx / "token":"xxx" / api_key: xxx 等(兼容 JSON 引号包裹 + 键值间空白, 值长度≥1)
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(password|passwd|pwd|token|api[_-]?key|secret|access[_-]?key|private[_-]?key|credential|2fa)" +
            "[\"']?\\s*[=:]\\s*[\"']?([^\\s\"',;}）]{1,})");
    // 连接串口令: ://user:pass@host(保留 user, 掩码 pass)
    private static final Pattern SECRET_URI = Pattern.compile("(://[^:/?#@\\s]+:)([^@/\\s]+)(@)");
    // bearer / authorization token
    private static final Pattern SECRET_BEARER = Pattern.compile("(?i)(bearer\\s+)([A-Za-z0-9._\\-]{8,})");
    // PEM 私钥块整体
    private static final Pattern SECRET_PEM = Pattern.compile("(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
    // 常见高熵密钥前缀整体串
    private static final Pattern SECRET_PREFIX = Pattern.compile("\\b(sk-[A-Za-z0-9_\\-]{8,}|AKIA[0-9A-Z]{12,}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{8,})\\b");

    /** 脱敏: 把常见 凭据(键值/连接串口令/bearer/PEM/已知前缀)替换为 ***REDACTED***(任何持久化文本必过, 落地禁写凭据红线)。 */
    public static String redactSecrets(String text) {
        return SecretRedactor.redact(text);
    }

    /** 剥除 NUL 与控制字符（保留 \n \r \t）。null → ""。 */
    public static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') { sb.append(c); continue; }
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 抠出所有 &lt;tool_call&gt; 内的 JSON 串；无标签则尝试整体 extractJson 且含 "name"。 */
    public static List<String> parseToolCalls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = TOOL_CALL.matcher(text);
        while (m.find()) {
            String json = extractJson(m.group(1));
            if (json != null) out.add(json);
        }
        if (out.isEmpty()) {
            String json = extractJson(stripThink(text)); // 剥 think 再兜底抠, 避免推理里的花括号误判
            if (json != null && json.contains("\"name\"")) out.add(json);
        }
        return out;
    }

    /** 剥除 &lt;tool_call&gt;...&lt;/tool_call&gt; 块与残留标签, 防工具调用 JSON 泄漏进面向用户的终答(模型在收尾轮仍硬吐工具调用时尤需)。 */
    public static String stripToolCalls(String text) {
        if (text == null) return null;
        String t = TOOL_CALL.matcher(text).replaceAll(" ");
        return t.replaceAll("(?i)</?tool_call\\s*>", " ");
    }

    /** 面向用户终答统一清洗: 去 &lt;think&gt; 过程留痕 + 去 &lt;tool_call&gt; 块 + 控制字符 sanitize。 */
    public static String cleanFinal(String text) {
        return sanitize(stripToolCalls(stripThink(text)));
    }

    /** 从可能含 ``` 围栏/散文的文本抠出首个平衡 {...} JSON 子串；无则 null。 */
    public static String extractJson(String text) {
        if (text == null) return null;
        String t = text;
        int fence = t.indexOf("```");
        if (fence >= 0) {
            int nl = t.indexOf('\n', fence);
            int end = t.indexOf("```", (nl >= 0 ? nl : fence) + 1);
            if (nl >= 0 && end > nl) t = t.substring(nl + 1, end);
        }
        int start = t.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        char prev = 0;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (c == '"' && prev != '\\') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return t.substring(start, i + 1);
                }
            }
            prev = c;
        }
        return null;
    }
}
