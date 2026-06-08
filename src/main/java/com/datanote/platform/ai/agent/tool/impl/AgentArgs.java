package com.datanote.platform.ai.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具入参抽取 + _deeplink 构造 共享助手（包内复用，零依赖）。 */
final class AgentArgs {

    private AgentArgs() {}

    static String str(JsonNode args, String key) {
        if (args == null) return null;
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    static Long longVal(JsonNode args, String key) {
        if (args == null) return null;
        JsonNode v = args.get(key);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    static int intVal(JsonNode args, String key, int def) {
        Long l = longVal(args, key);
        return l == null ? def : l.intValue();
    }

    /** 深链：跳数据地图打开表详情（ctx 用既有键 openTable）。 */
    static Map<String, Object> openTableLink(String db, String table) {
        Map<String, Object> ot = new LinkedHashMap<>();
        ot.put("db", db);
        ot.put("table", table);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("openTable", ot);
        return link("catalog", ctx);
    }

    /** 深链：跳治理-血缘并以该表为中心（ctx 用既有键 gov+table）。 */
    static Map<String, Object> lineageLink(String db, String table) {
        Map<String, Object> tbl = new LinkedHashMap<>();
        tbl.put("db", db);
        tbl.put("table", table);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("gov", "lineage");
        ctx.put("table", tbl);
        return link("governance", ctx);
    }

    static Map<String, Object> link(String route, Map<String, Object> ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("route", route);
        m.put("ctx", ctx);
        return m;
    }
}
