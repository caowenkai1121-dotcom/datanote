package com.datanote.sync.dialect;

/**
 * 建表 DDL 公共助手：comment 拼接与 SQL 字面量转义。
 * 由 MysqlDialect/DorisDialect 共用，保证注释转义 byte-identical。
 */
final class DdlSupport {

    private DdlSupport() {
    }

    static String comment(String c) {
        if (c == null || c.isEmpty()) {
            return "";
        }
        return " COMMENT '" + escapeSqlLiteral(c) + "'";
    }

    /** 转义 SQL 字符串字面量中的反斜杠、单引号与会破坏 DDL 的控制符。 */
    static String escapeSqlLiteral(String c) {
        StringBuilder sb = new StringBuilder(c.length() + 8);
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\0': break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }
}
