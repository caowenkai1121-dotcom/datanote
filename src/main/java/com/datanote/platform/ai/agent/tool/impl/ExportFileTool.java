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
 * export_file：把生成的【文本数据】导出为可下载文件(CSV/TXT/JSON/Markdown/XML), 存入数据中心供用户下载。
 * 用户要"导出/下载一个 csv/报表"时调用。只写文件存储(非业务数据, 不触审批)。返回下载链接。
 */
@Component
@RequiredArgsConstructor
public class ExportFileTool implements AiTool {

    private final AiFileService fileService;

    @Override public String name() { return "export_file"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "把你生成的文本数据导出为可下载文件(CSV/TXT/JSON/Markdown 等), 存入『数据中心』供用户下载。"
                + "用户说『导出/下载一个csv/报表/清单』时调用。参数 fileName(带扩展名, 如 '2026销量榜.csv') + content(完整文件文本, 如 CSV 全文)。返回下载链接。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"fileName\":{\"type\":\"string\",\"required\":true,\"desc\":\"带扩展名, 如 报表.csv\"},"
                + "\"content\":{\"type\":\"string\",\"required\":true,\"desc\":\"完整文件文本内容\"}}";
    }
    @Override public boolean readOnly() { return true; } // 仅写文件存储, 非业务数据/非永久禁区, 不触审批
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String fileName = AgentArgs.str(args, "fileName");
        String content = AgentArgs.str(args, "content");
        if (fileName == null) return AiToolResult.fail("bad_arguments", "fileName 不能为空(带扩展名)");
        if (content == null) content = "";
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String ct = guessContentType(fileName);
            DnAiFile m = fileService.saveContent(fileName, bytes, ct, ctx == null ? null : ctx.getUserName(),
                    ctx == null ? null : ctx.getSessionId(), "agent");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", m.getId());
            d.put("fileName", m.getFileName());
            d.put("size", m.getSizeBytes());
            d.put("downloadUrl", "/api/ai/agent/files/" + m.getId() + "/download");
            d.put("note", "已导出到数据中心, 用户可在左侧『已上传文件』或此链接下载");
            return AiToolResult.ok(d);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", "导出失败: " + e.getMessage());
        }
    }

    private static String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (n.endsWith(".xml")) return "application/xml; charset=utf-8";
        return "text/plain; charset=utf-8";
    }
}
