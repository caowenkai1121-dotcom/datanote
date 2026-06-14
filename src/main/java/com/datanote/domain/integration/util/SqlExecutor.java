package com.datanote.domain.integration.util;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 多语句 SQL 拆分与执行（前后置 SQL 用）。 */
public final class SqlExecutor {

    private SqlExecutor() {}

    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) return out;
        for (String part : splitOnSemicolons(sql)) {
            StringBuilder b = new StringBuilder();
            for (String line : part.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                if (b.length() > 0) b.append(" ");
                b.append(t);
            }
            String stmt = b.toString().trim();
            if (!stmt.isEmpty()) out.add(stmt);
        }
        return out;
    }

    /** 按分号拆分,但跳过单引号/反引号包裹区间内的分号,避免字符串字面量中的分号被错误切断。 */
    private static List<String> splitOnSemicolons(String sql) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0; // 0=不在引号内, 否则为当前引号字符(' 或 `)
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                cur.append(c);
                // 连续两个相同引号视为转义的字面引号(MySQL '' / ``)，跳过其闭合作用
                if (c == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { cur.append(sql.charAt(++i)); }
                    else quote = 0;
                }
            } else if (c == '\'' || c == '`') {
                quote = c;
                cur.append(c);
            } else if (c == ';') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    public static void execute(Connection conn, String sql) throws Exception {
        for (String stmt : splitStatements(sql)) {
            try (Statement s = conn.createStatement()) { s.execute(stmt); }
        }
    }
}
