package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.engine.AiFileService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * file_read：读取用户在【数据中心】上传的文件内容(供 agent 分析)。
 * 支持文本类(csv/txt/json/md/xml/log)直读; 二进制(xlsx/pdf/图片)返提示。owner 作用域。只读。
 * 上方"# 已上传文件"段已列出可用 fileId, 据此读取。
 */
@Component
@RequiredArgsConstructor
public class FileReadTool implements AiTool {

    private final AiFileService fileService;
    private static final int MAX_CHARS = 8000;

    @Override public String name() { return "file_read"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "读取用户在数据中心上传的文件内容(供分析)。参数 fileId(上方『已上传文件』段中的 id)。"
                + "支持 csv/txt/json/md/xml/log 直读, 及 xlsx(取首个工作表 CSV 化); pdf/老式xls/图片暂不支持。需分析用户上传数据时调用。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"fileId\":{\"type\":\"number\",\"required\":true,\"desc\":\"已上传文件的 id\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        int fileId = AgentArgs.intVal(args, "fileId", -1);
        if (fileId < 0) return AiToolResult.fail("bad_arguments", "fileId 必须为非负整数");
        String owner = ctx == null ? null : ctx.getUserName();
        Object[] r = fileService.readText((long) fileId, owner, MAX_CHARS);
        if (r == null) return AiToolResult.fail("not_found", "文件不存在或无权访问: id=" + fileId);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fileId", fileId);
        d.put("fileName", r[0]);
        d.put("isText", r[2]);
        d.put("content", r[1]);
        return AiToolResult.ok(d);
    }
}
