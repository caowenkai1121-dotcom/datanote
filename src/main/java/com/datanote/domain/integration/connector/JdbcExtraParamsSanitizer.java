package com.datanote.domain.integration.connector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JDBC extraParams 清洗(P2-04): 数据源 extraParams 原样拼入 JDBC URL 可注入危险驱动属性
 * (MySQL allowLoadLocalInfile/autoDeserialize/allowMultiQueries 等 → 本地文件读/反序列化RCE/多语句注入)。
 * 按分隔符拆 key=value, 丢弃危险键与含注释(#)的项。纯静态可单测。
 */
public final class JdbcExtraParamsSanitizer {

    private JdbcExtraParamsSanitizer() {}

    /** 危险 JDBC 连接属性(小写): 文件读取/反序列化/多语句等攻击面。 */
    private static final Set<String> DANGEROUS = new HashSet<>(Arrays.asList(
            "allowloadlocalinfile", "allowurlinlocalinfile", "allowlocalinfile", "uselocalinfile",
            "autodeserialize", "allowmultiqueries", "queryinterceptors", "statementinterceptors",
            "detectcustomcollations", "servertimezone#" /* 占位防误配 */));

    /**
     * 清洗 extraParams: 按 sep 拆项, 丢弃危险键/含 # 注释项; 保留其余。null/空 → ""。
     * @param sep MySQL/PG 用 '&', SQLServer 用 ';'
     */
    public static String sanitize(String extraParams, char sep) {
        if (extraParams == null || extraParams.trim().isEmpty()) return "";
        String[] parts = extraParams.split(Pattern.quote(String.valueOf(sep)));
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.indexOf('#') >= 0) continue;   // 注释截断: 防 "#" 之后内容绕过/注释掉安全默认项
            int eq = t.indexOf('=');
            String key = (eq >= 0 ? t.substring(0, eq) : t).trim().toLowerCase();
            if (DANGEROUS.contains(key)) continue;
            kept.add(t);
        }
        return String.join(String.valueOf(sep), kept);
    }
}
