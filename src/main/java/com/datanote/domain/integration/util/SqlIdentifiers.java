package com.datanote.domain.integration.util;

import java.util.regex.Pattern;

/**
 * SQL 标识符（库名/表名/列名）安全处理：白名单校验 + 反引号引用，杜绝注入。
 */
public final class SqlIdentifiers {

    /** 仅允许字母、数字、下划线、$ */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_$]+$");

    private SqlIdentifiers() {
    }

    public static boolean isValid(String identifier) {
        return identifier != null && VALID.matcher(identifier).matches();
    }

    /**
     * 校验并用反引号包裹标识符。
     *
     * @throws IllegalArgumentException 非法标识符
     */
    public static String quote(String identifier) {
        if (!isValid(identifier)) {
            throw new IllegalArgumentException("非法 SQL 标识符: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
