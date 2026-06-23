package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.engine.AiFileService;
import com.datanote.platform.ai.agent.model.DnAiFile;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * create_artifact：把多格式内容(markdown/mermaid/code/csv/json/svg/html)统一包成精美 HTML 页存入数据中心,
 * 返回可【点击右侧预览】的链接(像 Claude Artifacts / Codex)。服务端按 type 套对应渲染模板(marked/mermaid/prism/表格),
 * 复用 create_page 同一 _page 通道 + /view 沙箱预览。只写文件存储, 不触审批。
 */
@Component
@RequiredArgsConstructor
public class CreateArtifactTool implements AiTool {

    private final AiFileService fileService;

    @Override public String name() { return "create_artifact"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "把内容生成可【右侧预览】的精美页面(像 Claude Artifacts)。按内容选 type, 一律渲染为美观页面:\n"
                + " · markdown: 报告/方案/文档(自动排版标题/表格/代码块)\n"
                + " · mermaid: ER图/流程图/时序图/类图(content 填 mermaid 源码, 不含```围栏)\n"
                + " · code: 代码片段(语法高亮, 另填 language 如 sql/python/java)\n"
                + " · csv: 表格数据(content 填 CSV 全文, 渲染为可排序表格)\n"
                + " · json: JSON 数据(语法高亮)\n · svg: 矢量图(content 填 <svg>…</svg>)\n · html: 完整 HTML 文档(自带样式)\n"
                + "报告/文档优先 markdown(服务端渲染最稳)。html 若引外部库(echarts等), 务必用国内可达 CDN『https://cdn.staticfile.org/...』, 禁用 jsdelivr/cdnjs/unpkg(国内常被墙→白屏)。\n"
                + "参数 type + title + content(+ language, code时)。返回后只需一句话告诉用户『点右侧预览查看』, 【不要】把源码贴进答复。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"type\":{\"type\":\"string\",\"required\":true,\"desc\":\"markdown/mermaid/code/csv/json/svg/html\"},"
                + "\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"标题\"},"
                + "\"content\":{\"type\":\"string\",\"required\":true,\"desc\":\"内容(按 type: md正文/mermaid源码/代码/CSV全文/JSON/svg标签/html文档)\"},"
                + "\"language\":{\"type\":\"string\",\"required\":false,\"desc\":\"code 时的语言, 如 sql/python/java\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String type = lower(AgentArgs.str(args, "type"));
        String title = AgentArgs.str(args, "title");
        String content = AgentArgs.str(args, "content");
        String language = AgentArgs.str(args, "language");
        if (content == null || content.trim().isEmpty()) return AiToolResult.fail("bad_arguments", "content 不能为空");
        if (title == null || title.trim().isEmpty()) title = "内容";
        if (type == null || type.isEmpty()) type = "markdown";
        if (!"html".equals(type) && !"htm".equals(type)) content = stripFence(content); // 剥 LLM 误加的外层```围栏(html除外)
        String html;
        try {
            html = render(type, title, content, language);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", "渲染失败: " + e.getMessage());
        }
        String fileName = sanitize(title) + ".html";
        try {
            DnAiFile m = fileService.saveContent(fileName, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8",
                    ctx == null ? null : ctx.getUserName(), ctx == null ? null : ctx.getSessionId(), "agent");
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", m.getId());
            page.put("fileName", m.getFileName());
            page.put("title", title);
            page.put("artifactType", type);
            page.put("previewUrl", "/api/ai/agent/files/" + m.getId() + "/view");
            page.put("downloadUrl", "/api/ai/agent/files/" + m.getId() + "/download");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("_page", page); // 复用 create_page 通道: 前端卡片点击右侧预览
            d.put("note", type + " artifact 已生成, 对话中已给出『预览』卡片。答复里用一句话告知点右侧预览, 不要贴源码。");
            return AiToolResult.ok(d);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", "保存失败: " + e.getMessage());
        }
    }

    /** 按 type 渲染为完整 HTML 页。 */
    private String render(String type, String title, String content, String language) {
        switch (type) {
            case "html": case "htm":
                return content; // 完整 HTML 文档原样
            case "mermaid":
                // 国内可靠 CDN(staticfile/七牛), 替代被墙的 jsdelivr; ESM 动态导入兜底
                return shell(title, "mermaid",
                        "<script src=\"https://cdn.staticfile.org/mermaid/10.6.1/mermaid.min.js\"></script>",
                        "<div class=\"mermaid\">" + esc(content) + "</div>",
                        "try{mermaid.initialize({startOnLoad:true,theme:'default',securityLevel:'loose'});}catch(e){document.querySelector('.mermaid').insertAdjacentHTML('beforebegin','<p style=\\'color:#b91c1c\\'>图表库加载失败(网络受限), 源码如下:</p>');}");
            case "markdown": case "md":
                // 服务端渲染, 零 CDN 依赖, 国内稳定
                return shell(title, "markdown", "", "<div class=\"md-body\">" + mdToHtml(content) + "</div>", null);
            case "code":
                return shell(title, "code · " + (language == null ? "text" : language), "",
                        "<pre><code>" + esc(content) + "</code></pre>", null);
            case "json":
                return shell(title, "json", "", "<pre><code>" + esc(content) + "</code></pre>", null);
            case "svg":
                return shell(title, "svg", "", "<div class=\"svg-box\">" + content + "</div>", null);
            case "csv":
                return shell(title, "csv", "", csvToTable(content), CSV_SORT_JS);
            default: // 当作 markdown(服务端渲染)
                return shell(title, type, "", "<div class=\"md-body\">" + mdToHtml(content) + "</div>", null);
        }
    }

    /** 统一精美外壳: 头部标题+类型徽章, 通用排版样式。 */
    private String shell(String title, String badge, String head, String body, String script) {
        return "<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(title) + "</title>"
                + (head == null ? "" : head)
                + "<style>" + STYLE + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"hd\"><span class=\"title\">" + esc(title) + "</span><span class=\"badge\">" + esc(badge) + "</span></div>"
                + "<div class=\"content\">" + body + "</div></div>"
                + (script == null ? "" : "<script>" + script + "</script>")
                + "</body></html>";
    }

    /** 朴素 CSV → 可排序表格(支持双引号包裹的逗号)。 */
    private String csvToTable(String csv) {
        StringBuilder sb = new StringBuilder("<table id=\"t\"><thead><tr>");
        String[] lines = csv.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        boolean head = true;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            java.util.List<String> cells = splitCsv(line);
            if (head) {
                for (String c : cells) sb.append("<th onclick=\"sortT(this)\">").append(esc(c)).append("</th>");
                sb.append("</tr></thead><tbody>");
                head = false;
            } else {
                sb.append("<tr>");
                for (String c : cells) sb.append("<td>").append(esc(c)).append("</td>");
                sb.append("</tr>");
            }
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static java.util.List<String> splitCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; }
                else cur.append(c);
            } else {
                if (c == '"') q = true;
                else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static final String CSV_SORT_JS =
            "function sortT(th){var t=document.getElementById('t'),tb=t.tBodies[0],i=[].indexOf.call(th.parentNode.children,th);"
            + "var rows=[].slice.call(tb.rows);var asc=th._a=!th._a;rows.sort(function(a,b){var x=a.cells[i].textContent.trim(),y=b.cells[i].textContent.trim();"
            + "var nx=parseFloat(x.replace(/,/g,'')),ny=parseFloat(y.replace(/,/g,''));var r=(!isNaN(nx)&&!isNaN(ny))?nx-ny:x.localeCompare(y,'zh');return asc?r:-r;});"
            + "rows.forEach(function(r){tb.appendChild(r);});}";

    private static final String STYLE =
            "*{box-sizing:border-box}body{margin:0;padding:28px 24px 48px;background:#f6f7f9;"
            + "font-family:system-ui,-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#1f2937;line-height:1.7}"
            + ".wrap{max-width:1000px;margin:0 auto;background:#fff;border-radius:14px;box-shadow:0 1px 3px rgba(0,0,0,.06),0 8px 30px rgba(0,0,0,.04);overflow:hidden}"
            + ".hd{display:flex;align-items:center;gap:12px;padding:18px 26px;border-bottom:1px solid #eef0f3;background:linear-gradient(135deg,#fbfcff,#f4f6fb)}"
            + ".hd .title{font-size:18px;font-weight:650;color:#111827;flex:1}"
            + ".hd .badge{font-size:11px;font-weight:600;color:#6366f1;background:#eef2ff;border:1px solid #e0e7ff;padding:3px 10px;border-radius:20px;text-transform:uppercase;letter-spacing:.5px}"
            + ".content{padding:26px 30px}"
            + ".md-body h1,.md-body h2,.md-body h3{color:#111827;margin:1.2em 0 .5em;font-weight:650}"
            + ".md-body h1{font-size:24px;border-bottom:2px solid #6366f1;padding-bottom:8px}.md-body h2{font-size:19px}.md-body h3{font-size:16px}"
            + ".md-body p{margin:.6em 0}.md-body code{background:#f1f5f9;color:#db2777;padding:2px 6px;border-radius:4px;font-size:.9em}"
            + ".md-body pre{background:#0f172a;color:#e2e8f0;padding:16px;border-radius:10px;overflow:auto}.md-body pre code{background:none;color:inherit;padding:0}"
            + ".md-body blockquote{border-left:4px solid #c7d2fe;margin:.8em 0;padding:.4em 1em;color:#475569;background:#f8fafc}"
            + ".md-body .tbl-wrap{overflow-x:auto;margin:1em 0}.md-body table{border-collapse:collapse;width:100%;font-size:14px}.md-body th,.md-body td{border:1px solid #e5e7eb;padding:8px 12px;text-align:left}.md-body th{background:#f3f4f6}"
            + ".md-body a{color:#4f46e5}.md-body ul,.md-body ol{padding-left:1.6em}"
            + "pre{background:#1e293b;color:#e2e8f0;padding:18px;border-radius:10px;overflow:auto;font-size:13px;line-height:1.6;margin:0}"
            + "table{border-collapse:collapse;width:100%;font-size:13px}"
            + "th,td{border:1px solid #e5e7eb;padding:8px 12px;text-align:left;white-space:nowrap}"
            + "th{background:#f3f4f6;cursor:pointer;position:sticky;top:0;user-select:none}th:hover{background:#e9ebef}"
            + "tr:nth-child(even){background:#fafbfc}"
            + ".svg-box{display:flex;justify-content:center;padding:10px}.svg-box svg{max-width:100%;height:auto}"
            + ".mermaid{display:flex;justify-content:center}"
            + "@media print{body{background:#fff;padding:0}.wrap{box-shadow:none;border:0;max-width:none}.hd{background:#fff}pre{white-space:pre-wrap}}"; // Ctrl+P 存干净 PDF

    /** 服务端 Markdown→HTML(零 CDN, 自包含; 国内可靠预览)。覆盖标题/列表/表格/代码块/引用/分割线/粗斜体/行内码/链接。 */
    public static String mdToHtml(String md) {
        if (md == null) return "";
        String[] lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inUl = false, inOl = false;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            String t = ln.trim();
            // 代码块 ```
            if (t.startsWith("```")) {
                if (inUl) { out.append("</ul>"); inUl = false; }
                if (inOl) { out.append("</ol>"); inOl = false; }
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) { code.append(esc(lines[i])).append('\n'); i++; }
                out.append("<pre><code>").append(code).append("</code></pre>");
                continue;
            }
            // 表格: 当前行含 | 且下一行是分隔(|---|)
            if (t.contains("|") && i + 1 < lines.length && lines[i + 1].trim().matches("\\|?[\\s:|-]*-[\\s:|-]*\\|?")) {
                if (inUl) { out.append("</ul>"); inUl = false; }
                if (inOl) { out.append("</ol>"); inOl = false; }
                out.append("<div class=\"tbl-wrap\"><table><thead><tr>");
                for (String c : splitRow(t)) out.append("<th>").append(inline(c.trim())).append("</th>");
                out.append("</tr></thead><tbody>");
                i += 2; // 跳过表头与分隔行
                while (i < lines.length && lines[i].trim().contains("|")) {
                    out.append("<tr>");
                    for (String c : splitRow(lines[i].trim())) out.append("<td>").append(inline(c.trim())).append("</td>");
                    out.append("</tr>"); i++;
                }
                i--; out.append("</tbody></table></div>");
                continue;
            }
            if (t.isEmpty()) { if (inUl) { out.append("</ul>"); inUl = false; } if (inOl) { out.append("</ol>"); inOl = false; } continue; }
            // 分割线
            if (t.matches("(-{3,}|\\*{3,}|_{3,})")) { if (inUl) { out.append("</ul>"); inUl = false; } if (inOl) { out.append("</ol>"); inOl = false; } out.append("<hr>"); continue; }
            // 标题
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(t);
            if (hm.matches()) { if (inUl) { out.append("</ul>"); inUl = false; } if (inOl) { out.append("</ol>"); inOl = false; }
                int lv = hm.group(1).length(); out.append("<h").append(lv).append(">").append(inline(hm.group(2))).append("</h").append(lv).append(">"); continue; }
            // 引用
            if (t.startsWith("> ")) { if (inUl) { out.append("</ul>"); inUl = false; } if (inOl) { out.append("</ol>"); inOl = false; }
                out.append("<blockquote>").append(inline(t.substring(2))).append("</blockquote>"); continue; }
            // 有序列表
            if (t.matches("\\d+\\.\\s+.*")) { if (inUl) { out.append("</ul>"); inUl = false; } if (!inOl) { out.append("<ol>"); inOl = true; }
                out.append("<li>").append(inline(t.replaceFirst("\\d+\\.\\s+", ""))).append("</li>"); continue; }
            // 无序列表
            if (t.matches("[-*+]\\s+.*")) { if (inOl) { out.append("</ol>"); inOl = false; } if (!inUl) { out.append("<ul>"); inUl = true; }
                out.append("<li>").append(inline(t.replaceFirst("[-*+]\\s+", ""))).append("</li>"); continue; }
            // 段落
            if (inUl) { out.append("</ul>"); inUl = false; }
            if (inOl) { out.append("</ol>"); inOl = false; }
            out.append("<p>").append(inline(t)).append("</p>");
        }
        if (inUl) out.append("</ul>");
        if (inOl) out.append("</ol>");
        return out.toString();
    }
    private static String[] splitRow(String row) {
        String r = row;
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        return r.split("\\|", -1);
    }
    /** 行内: 先转义, 再处理 行内码 / 粗体 / 斜体 / 链接。 */
    private static String inline(String s) {
        String x = esc(s);
        x = x.replaceAll("`([^`]+)`", "<code>$1</code>");
        x = x.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        x = x.replaceAll("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])", "<em>$1</em>"); // 紧邻非空格才算斜体, 避免 a * b 误判
        x = x.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\" target=\"_blank\" rel=\"noopener\">$1</a>");
        return x;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escScript(String s) { // 防 </script> 提前闭合
        return s == null ? "" : s.replace("</", "<\\/");
    }
    /** 剥除内容外层 ```lang ... ``` 围栏(LLM 常误包)。 */
    private static String stripFence(String c) {
        String t = c.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) { t = t.substring(nl + 1); if (t.endsWith("```")) t = t.substring(0, t.length() - 3); return t.trim(); }
        }
        return c;
    }
    private static String lower(String s) { return s == null ? null : s.trim().toLowerCase(); }
    private static String sanitize(String t) {
        String n = t.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "").trim();
        if (n.isEmpty()) n = "内容";
        return n.length() > 60 ? n.substring(0, 60) : n;
    }
}
