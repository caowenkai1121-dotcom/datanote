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
 * create_page：把生成的【完整 HTML 网页】存入数据中心, 返回可【点击右侧预览】的链接(前端点击即在右侧面板沙箱渲染)。
 * 适合可视化报表 / ER 图(mermaid) / 图表(echarts/chart.js) / 数据看板 / 富文本页面。
 * 安全: 仅写文件存储(非业务数据, 不触审批); 预览经 /view 端点 CSP sandbox 不透明源隔离, 防 XSS/CSRF。
 */
@Component
@RequiredArgsConstructor
public class CreatePageTool implements AiTool {

    private final AiFileService fileService;

    @Override public String name() { return "create_page"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "把你生成的【完整 HTML 网页】存入数据中心并返回可【点击右侧预览】的链接(用户点击即在右侧面板渲染网页, 像 Codex/Claude 的 artifact)。"
                + "适合: 可视化报表 / ER 图(在 html 内用 mermaid CDN) / 图表(echarts/chart.js CDN) / 数据看板 / 富文本页面。"
                + "参数 title(页面标题) + html(完整 HTML 文档文本, 须含 <!DOCTYPE html><html>…; 需图表/ER图请在 html 内引 CDN 脚本自渲染)。"
                + "返回后请用一句话告诉用户『点右侧预览查看』, 不要把 HTML 源码贴进答复。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"页面标题\"},"
                + "\"html\":{\"type\":\"string\",\"required\":true,\"desc\":\"完整HTML文档文本(<!DOCTYPE html>...)\"}}";
    }
    @Override public boolean readOnly() { return true; } // 仅写文件存储, 非业务数据/非永久禁区, 不触审批
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String title = AgentArgs.str(args, "title");
        String html = AgentArgs.str(args, "html");
        if (html == null || html.trim().isEmpty()) return AiToolResult.fail("bad_arguments", "html 不能为空(完整 HTML 文档)");
        if (title == null || title.trim().isEmpty()) title = "页面";
        String fileName = sanitizeTitle(title) + ".html";
        try {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            DnAiFile m = fileService.saveContent(fileName, bytes, "text/html; charset=utf-8",
                    ctx == null ? null : ctx.getUserName(), ctx == null ? null : ctx.getSessionId(), "agent");
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", m.getId());
            page.put("fileName", m.getFileName());
            page.put("title", title);
            page.put("previewUrl", "/api/ai/agent/files/" + m.getId() + "/view");
            page.put("downloadUrl", "/api/ai/agent/files/" + m.getId() + "/download");
            page.put("size", m.getSizeBytes());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("_page", page); // 标记: run 循环收集到 previews 通道, 前端渲染"点击右侧预览"卡片
            d.put("note", "网页已生成, 对话中已给出『预览』卡片(用户点击即在右侧渲染)。答复里用一句话告知可点右侧预览, 不要贴 HTML 源码。");
            return AiToolResult.ok(d);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", "生成网页失败: " + e.getMessage());
        }
    }

    private static String sanitizeTitle(String t) {
        String n = t.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "").trim();
        if (n.isEmpty()) n = "页面";
        return n.length() > 60 ? n.substring(0, 60) : n;
    }
}
