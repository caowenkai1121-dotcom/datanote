package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.AssetDetailService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读工具：表画像剖析（行数/字段空值率/distinct 等，下推数仓采样）。结果附 _deeplink。 */
@Component
@RequiredArgsConstructor
public class TableProfileTool implements AiTool {

    private final AssetDetailService assetDetailService;

    @Override public String name() { return "table_profile"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "对一张表做数据画像剖析(总行数 + 字段级空值数/空值率/distinct 基数)。判断数据质量/字段可用性。参数 db、table 必填(开销较大，按需调用)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = AgentArgs.str(args, "db");
            String table = AgentArgs.str(args, "table");
            if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profile", assetDetailService.profile(db, table));
            out.put("_deeplink", AgentArgs.openTableLink(db, table));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
