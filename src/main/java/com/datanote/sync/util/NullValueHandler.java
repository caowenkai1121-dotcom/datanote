package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;

/**
 * 空值处理：按 FieldMapping.nullHandling 决定原样/替默认/跳行。
 */
public final class NullValueHandler {

    /** 跳行哨兵：handle 返回此对象表示整行应被跳过。 */
    public static final Object SKIP = new Object();

    private NullValueHandler() {}

    public static Object handle(Object value, FieldMapping fm) {
        if (value != null || fm == null) return value;
        String nh = fm.getNullHandling();
        if ("REPLACE_WITH_DEFAULT".equalsIgnoreCase(nh)) return fm.getDefaultValue();
        if ("SKIP_ROW".equalsIgnoreCase(nh)) return SKIP;
        return null; // PASSTHROUGH / 未配置
    }
}
