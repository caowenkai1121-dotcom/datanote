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

/** 只读工具：表资产详情（字段元数据/分类/owner）。表体检第一步。结果附 _deeplink 回数据地图。 */
@Component
@RequiredArgsConstructor
public class AssetDetailTool implements AiTool {

    private final AssetDetailService assetDetailService;

    @Override public String name() { return "asset_detail"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "查一张表的资产详情(表元数据 + 字段级元数据/类型/注释/分类分级)。做表体检/资产理解的第一步。参数 db、table 必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "catalog:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String db = AgentArgs.str(args, "db");
            String table = AgentArgs.str(args, "table");
            if (db == null || table == null) return AiToolResult.fail("bad_arguments", "db 与 table 不能为空");
            Map<String, Object> detail = assetDetailService.assetDetail(db, table);
            // 表不存在: 返回【真实】相近表候选, 让 agent 据此求证/求助, 严禁臆造一个表名
            if (detail == null || detail.get("table") == null) {
                java.util.List<String> cands = assetDetailService.findSimilarTables(db, table, 8);
                String msg = "未找到表 " + db + "." + table + "。"
                        + (cands.isEmpty() ? "元数据库中无相近表。请勿臆造表名, 用 semantic_search 检索或 ask_user 向用户澄清。"
                        : "相近的真实表有: " + String.join("、", cands) + "。请确认正确库表名, 或用 ask_user 让用户选择, 切勿臆造。");
                return AiToolResult.fail("not_found", msg);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("detail", detail);
            out.put("_deeplink", AgentArgs.openTableLink(db, table));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
