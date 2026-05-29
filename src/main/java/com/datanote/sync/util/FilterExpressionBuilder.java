package com.datanote.sync.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 行过滤表达式 → WHERE 片段。列名白名单校验、op 白名单、值转义，防注入。 */
public final class FilterExpressionBuilder {

    private static final Set<String> OPS = new HashSet<>(Arrays.asList("=", "<>", ">", ">=", "<", "<=", "LIKE"));

    private FilterExpressionBuilder() {}

    public static String build(String filterExpression) {
        if (filterExpression == null || filterExpression.trim().isEmpty()) return "";
        JSONObject o = JSONObject.parseObject(filterExpression);
        JSONArray conds = o.getJSONArray("conditions");
        if (conds == null || conds.isEmpty()) return "";
        String logic = o.getString("logic");
        logic = "OR".equalsIgnoreCase(logic) ? " OR " : " AND ";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < conds.size(); i++) {
            JSONObject c = conds.getJSONObject(i);
            String col = c.getString("column");
            String op = c.getString("op");
            if (!SqlIdentifiers.isValid(col)) throw new IllegalArgumentException("非法过滤列: " + col);
            if (op == null || !OPS.contains(op.toUpperCase())) throw new IllegalArgumentException("非法过滤操作符: " + op);
            if (i > 0) sb.append(logic);
            sb.append("`").append(col).append("` ").append(op.toUpperCase()).append(" ").append(literal(c.get("value")));
        }
        return sb.append(")").toString();
    }

    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
