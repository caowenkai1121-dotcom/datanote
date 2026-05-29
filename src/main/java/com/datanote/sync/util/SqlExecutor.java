package com.datanote.sync.util;

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
        for (String part : sql.split(";")) {
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

    public static void execute(Connection conn, String sql) throws Exception {
        for (String stmt : splitStatements(sql)) {
            try (Statement s = conn.createStatement()) { s.execute(stmt); }
        }
    }
}
