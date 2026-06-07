package com.datanote.domain.integration.util;

import com.alibaba.fastjson2.JSONObject;

/**
 * 内置转换函数：按 transformExpression JSON 对单值做轻量清洗。
 * null 值/空表达式原样返回。
 */
public final class ValueTransformer {

    private ValueTransformer() {}

    public static Object transform(Object value, String expr) {
        if (value == null || expr == null || expr.trim().isEmpty()) return value;
        JSONObject o = JSONObject.parseObject(expr);
        String type = o.getString("type");
        if (type == null) return value;
        JSONObject a = o.getJSONObject("args");
        if (a == null) a = new JSONObject();
        String s = String.valueOf(value);
        switch (type.toLowerCase()) {
            case "upper": return s.toUpperCase();
            case "lower": return s.toLowerCase();
            case "trim":  return s.trim();
            case "substring": {
                int start = a.containsKey("start") ? a.getIntValue("start") : 0;
                int len   = a.containsKey("length") ? a.getIntValue("length") : s.length();
                if (start < 0) start = 0;
                if (start > s.length()) start = s.length();
                int end = Math.min(s.length(), start + Math.max(0, len));
                return s.substring(start, end);
            }
            case "replace": {
                String from = a.getString("from");
                if (from == null || from.isEmpty()) return value;
                String to = a.getString("to");
                if (to == null) to = "";
                return s.replace(from, to);
            }
            case "lpad": {
                int len = a.getIntValue("len", s.length());
                String pad = a.getString("pad");
                if (pad == null || pad.isEmpty()) pad = " ";
                if (s.length() >= len) return s;
                StringBuilder b = new StringBuilder();
                while (b.length() < len - s.length()) b.append(pad);
                return b.substring(0, len - s.length()) + s;
            }
            case "rpad": {
                int len = a.getIntValue("len", s.length());
                String pad = a.getString("pad");
                if (pad == null || pad.isEmpty()) pad = " ";
                if (s.length() >= len) return s;
                StringBuilder b = new StringBuilder(s);
                while (b.length() < len) b.append(pad);
                return b.substring(0, len);
            }
            case "dateformat": {
                String fmt = a.getString("format");
                if (fmt == null || fmt.isEmpty()) return value;
                java.util.Date d;
                if (value instanceof java.util.Date) {
                    d = (java.util.Date) value;
                } else {
                    try { d = new java.util.Date(Long.parseLong(s)); }
                    catch (NumberFormatException e) { return value; }
                }
                return new java.text.SimpleDateFormat(fmt).format(d);
            }
            default: return value;
        }
    }
}
