package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.develop.ScriptService;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 新建数据开发脚本(SQL/Shell/Python)。映射 UI 同款 ScriptService.save, 建成在数据开发文件树可见, 可调度执行。 */
@Component
@RequiredArgsConstructor
public class CreateScriptTool implements AiTool {

    private final ScriptService scriptService;

    @Override public String name() { return "create_script"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "新建数据开发脚本(写操作, 需人工审批)。把加工逻辑落成可调度脚本。建成在数据开发文件树可见。"
                + "参数 scriptName、content、folderId 必填(脚本须归属某文件夹, 可先用 create_dev_folder 建目录或用只读工具查现有目录ID); "
                + "scriptType(SQL/Shell/Python, 默认SQL)、databaseName、description 可选。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"scriptName\":{\"type\":\"string\",\"required\":true,\"desc\":\"脚本名\"},"
                + "\"content\":{\"type\":\"string\",\"required\":true,\"desc\":\"脚本内容(SQL/脚本正文)\"},"
                + "\"folderId\":{\"type\":\"number\",\"required\":true,\"desc\":\"所属文件夹ID(必填)\"},"
                + "\"scriptType\":{\"type\":\"string\",\"required\":false,\"desc\":\"SQL/Shell/Python,默认SQL\"},"
                + "\"databaseName\":{\"type\":\"string\",\"required\":false},"
                + "\"description\":{\"type\":\"string\",\"required\":false}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String scriptName = AgentArgs.str(args, "scriptName");
            String content = AgentArgs.str(args, "content");
            Long folderId = AgentArgs.longVal(args, "folderId");
            if (scriptName == null) return AiToolResult.fail("bad_arguments", "scriptName 不能为空");
            if (content == null) return AiToolResult.fail("bad_arguments", "content 不能为空");
            if (folderId == null) return AiToolResult.fail("bad_arguments", "folderId 不能为空(脚本须归属某文件夹)");
            DnScript s = new DnScript();
            s.setScriptName(scriptName);
            s.setContent(content);
            String type = AgentArgs.str(args, "scriptType");
            s.setScriptType(type == null ? "SQL" : type);
            s.setFolderId(folderId);
            s.setDatabaseName(AgentArgs.str(args, "databaseName"));
            s.setDescription(AgentArgs.str(args, "description"));
            if (ctx != null && ctx.getUserName() != null) { s.setCreatedBy(ctx.getUserName()); s.setUpdatedBy(ctx.getUserName()); }
            DnScript saved = scriptService.save(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            out.put("_deeplink", AgentArgs.link("develop", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
