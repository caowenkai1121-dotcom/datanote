package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.AssetDetailService;
import com.datanote.domain.governance.MaskingService;
import com.datanote.domain.governance.SqlMaskRewriter;
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
 * table_data：查看一张表的【真实样例数据行】(SELECT * LIMIT n, 默认20/上限50)。
 * 让用户在会话中直接看到表里的数据内容(前端把 _preview 渲染成数据表格)。只读探查。
 */
@Component
@RequiredArgsConstructor
public class TableDataTool implements AiTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final AssetDetailService assetDetailService;
    private final MaskingService maskingService;   // P0: AI 读样例对脱敏列 fail-closed 盖 ***, 防明文泄露

    @Override public String name() { return "table_data"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "查看一张表的【真实样例数据行】(默认20行, 最多50行), 让用户在会话中直接看到表里的数据。"
                + "用户说『看下xx表的数据/数据长什么样/抽几行看看/这表里有什么』时调用。参数 db、table 必填, limit 可选(默认20, 最多50)。"
                + "结果会以数据表格直接展示给用户。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"db\":{\"type\":\"string\",\"required\":true,\"desc\":\"库名\"},"
                + "\"table\":{\"type\":\"string\",\"required\":true,\"desc\":\"表名\"},"
                + "\"limit\":{\"type\":\"integer\",\"required\":false,\"desc\":\"返回行数, 默认20, 最多50\"}}";
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
            int limit = (args == null || args.get("limit") == null) ? DEFAULT_LIMIT : args.path("limit").asInt(DEFAULT_LIMIT);
            limit = Math.max(1, Math.min(limit, MAX_LIMIT)); // 与描述一致钳到[1,50], 不依赖下游兜底, 防超大/负数 SELECT
            Map<String, Object> preview;
            try {
                preview = assetDetailService.sampleRows(db, table, limit);
            } catch (Exception qe) {
                String m = qe.getMessage() == null ? "" : qe.getMessage().toLowerCase();
                // 表不存在: 给【真实】相近表候选, 让 agent 求证/求助勿臆造; 其它错误如实回传(诚实纪律)
                if (m.contains("does not exist") || m.contains("doesn't exist") || m.contains("not exist") || m.contains("unknown table") || m.contains("不存在")) {
                    java.util.List<String> cands = assetDetailService.findSimilarTables(db, table, 8);
                    String msg = "未找到表 " + db + "." + table + "。"
                            + (cands.isEmpty() ? "元数据库中无相近表。请勿臆造, 用 semantic_search 检索或 ask_user 澄清。"
                            : "相近的真实表有: " + String.join("、", cands) + "。请确认正确库表名或 ask_user 让用户选择, 切勿臆造数据。");
                    return AiToolResult.fail("not_found", msg);
                }
                return AiToolResult.fail("exec_failed", "查表数据失败: " + qe.getMessage());
            }
            maskPreview(preview, db, table);       // P0: 脱敏列盖 ***(fail-closed), AI/前端均不见明文
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("db", db);
            out.put("table", table);
            out.put("returned", preview.get("returned"));
            out.put("_preview", preview);          // 前端据此渲染数据表格(columns/rows)
            out.put("_deeplink", AgentArgs.openTableLink(db, table));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }

    /** 对配置了脱敏策略的列, 把样例结果该列值盖为 ***(fail-closed); columns/rows 位对齐。 */
    @SuppressWarnings("unchecked")
    private void maskPreview(Map<String, Object> preview, String db, String table) {
        if (preview == null) return;
        Object colsObj = preview.get("columns");
        Object rowsObj = preview.get("rows");
        if (!(colsObj instanceof java.util.List) || !(rowsObj instanceof java.util.List)) return;
        java.util.List<SqlMaskRewriter.ColumnMask> masks;
        try { masks = maskingService.resolveColumnMasks(); } catch (Exception e) { return; }
        if (masks == null || masks.isEmpty()) return;
        java.util.List<?> cols = (java.util.List<?>) colsObj;
        java.util.Set<Integer> maskedIdx = new java.util.HashSet<>();
        for (int i = 0; i < cols.size(); i++) {
            String cn = String.valueOf(cols.get(i));
            for (SqlMaskRewriter.ColumnMask m : masks) {
                if (m == null || m.getColumn() == null) continue;
                boolean dbOk = m.getDb() == null || m.getDb().isEmpty() || m.getDb().equalsIgnoreCase(db);
                boolean tbOk = m.getTable() == null || m.getTable().isEmpty() || m.getTable().equalsIgnoreCase(table);
                if (cn.equalsIgnoreCase(m.getColumn()) && dbOk && tbOk) { maskedIdx.add(i); break; }
            }
        }
        if (maskedIdx.isEmpty()) return;
        for (Object ro : (java.util.List<?>) rowsObj) {
            if (ro instanceof java.util.List) {
                java.util.List<Object> row = (java.util.List<Object>) ro;
                for (Integer idx : maskedIdx) {
                    if (idx < row.size() && row.get(idx) != null) row.set(idx, "***");
                }
            }
        }
    }
}
