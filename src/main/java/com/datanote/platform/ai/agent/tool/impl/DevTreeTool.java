package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.develop.ScriptService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读: 数据开发文件树(文件夹/脚本/数据源/ODS任务层级)。建脚本前用它查现有文件夹ID。封装 ScriptService.getTree。 */
@Component
@RequiredArgsConstructor
public class DevTreeTool implements AiTool {

    private final ScriptService scriptService;

    @Override public String name() { return "dev_tree"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "查数据开发文件树(文件夹/脚本层级及各自ID)。新建脚本(create_script)前可用它拿到目标文件夹ID。无参数。";
    }
    @Override public String paramsSchemaJson() {
        return "{}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            List<Map<String, Object>> tree = scriptService.getTree();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tree", tree);
            out.put("_deeplink", AgentArgs.link("develop", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
