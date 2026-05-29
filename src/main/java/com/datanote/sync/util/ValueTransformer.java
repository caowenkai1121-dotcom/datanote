package com.datanote.sync.util;

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
            case "replace": return s.replace(a.getString("from"), a.getString("to"));
            case "lpad": {
                int len = a.containsKey("len") ? a.getIntValue("len") : s.length();
                String pad = a.getString("pad");
                if (pad == null || pad.isEmpty()) pad = " ";
                StringBuilder b = new StringBuilder();
                while (b.length() + s.length() < len) b.append(pad);
                return b.append(s).toString();
            }
            case "rpad": {
                int len = a.containsKey("len") ? a.getIntValue("len") : s.length();
                String pad = a.getString("pad");
                if (pad == null || pad.isEmpty()) pad = " ";
                StringBuilder b = new StringBuilder(s);
                while (b.length() < len) b.append(pad);
                return b.toString();
            }
            case "dateformat": {
                String fmt = a.getString("format");
                java.util.Date d = (value instanceof java.util.Date)
                        ? (java.util.Date) value
                        : new java.util.Date(Long.parseLong(s));
                return new java.text.SimpleDateFormat(fmt).format(d);
            }
            default: return value;
        }
    }
}
