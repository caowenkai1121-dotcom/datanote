package com.datanote.platform.ai.agent.engine;

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
            String json = extractJson(text);
            if (json != null && json.contains("\"name\"")) out.add(json);
        }
        return out;
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
