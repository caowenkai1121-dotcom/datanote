package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.develop.ScriptService;
import com.datanote.domain.develop.model.DnScriptFolder;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 新建数据开发文件夹(脚本目录)。映射 UI 同款 ScriptService.createFolder, 建成在数据开发文件树可见。 */
@Component
@RequiredArgsConstructor
public class CreateDevFolderTool implements AiTool {

    private final ScriptService scriptService;

    @Override public String name() { return "create_dev_folder"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "新建数据开发文件夹/脚本目录(写操作, 需人工审批)。用于归类组织脚本。建成在数据开发文件树可见。"
                + "参数 folderName 必填; parentId(父目录ID, 默认0=根)、layer(分层标签) 可选。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"folderName\":{\"type\":\"string\",\"required\":true,\"desc\":\"文件夹名\"},"
                + "\"parentId\":{\"type\":\"number\",\"required\":false,\"desc\":\"父目录ID,默认0=根\"},"
                + "\"layer\":{\"type\":\"string\",\"required\":false,\"desc\":\"分层标签\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String folderName = AgentArgs.str(args, "folderName");
            if (folderName == null) return AiToolResult.fail("bad_arguments", "folderName 不能为空");
            DnScriptFolder f = new DnScriptFolder();
            f.setFolderName(folderName);
            Long parentId = AgentArgs.longVal(args, "parentId");
            f.setParentId(parentId == null ? 0L : parentId);
            f.setLayer(AgentArgs.str(args, "layer"));
            DnScriptFolder saved = scriptService.createFolder(f);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            out.put("_deeplink", AgentArgs.link("develop", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
