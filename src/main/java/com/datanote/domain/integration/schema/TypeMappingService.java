package com.datanote.domain.integration.schema;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 列类型 → 目标库类型映射。第一版支持 MySQL→Doris/StarRocks。
 * MySQL→MySQL 直接照搬源类型，无需经过本服务。
 */
@Service
public class TypeMappingService {

    private static final Pattern LEN = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern DECIMAL = Pattern.compile("decimal\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)");

    /**
     * 把 MySQL 的 COLUMN_TYPE（如 varchar(50)、int(11) unsigned、decimal(10,2)）映射为 Doris 类型。
     */
    public String mysqlToDoris(String mysqlColumnType) {
        if (mysqlColumnType == null) {
            return "STRING";
        }
        String t = mysqlColumnType.trim().toLowerCase();
        // unsigned 整型上界更大，需升一档类型避免目标列溢出
        boolean unsigned = t.contains("unsigned");

        if (t.startsWith("tinyint")) return unsigned ? "SMALLINT" : "TINYINT";
        if (t.startsWith("smallint")) return unsigned ? "INT" : "SMALLINT";
        if (t.startsWith("mediumint")) return "INT";
        if (t.startsWith("bigint")) return unsigned ? "LARGEINT" : "BIGINT";
        if (t.startsWith("int") || t.startsWith("integer")) return unsigned ? "BIGINT" : "INT";
        if (t.startsWith("float")) return "FLOAT";
        if (t.startsWith("double") || t.startsWith("real")) return "DOUBLE";
        if (t.startsWith("decimal") || t.startsWith("numeric")) {
            Matcher m = DECIMAL.matcher(t);
            if (m.find()) {
                int p = parseIntSafe(m.group(1), 38);   // 超大括号数字会溢出,兜底到上限
                int s = parseIntSafe(m.group(2), 0);
                // Doris DECIMAL 精度上限 38
                if (p > 38) { p = 38; }
                if (s > p) { s = p; }
                return "DECIMAL(" + p + "," + s + ")";
            }
            return "DECIMAL(10,0)";
        }
        if (t.startsWith("datetime") || t.startsWith("timestamp")) {
            int p = precisionOrDefault(t, 0);   // 保留毫秒/微秒精度，避免截断
            return p > 0 ? "DATETIME(" + Math.min(p, 6) + ")" : "DATETIME";
        }
        if (t.startsWith("date")) return "DATE";
        if (t.startsWith("char")) {
            // Doris CHAR 上限 255 字节；超出退化 VARCHAR/STRING
            int bytes = lenOrDefault(t, 1) * 3;
            if (bytes <= 255) return "CHAR(" + bytes + ")";
            return bytes > 65533 ? "STRING" : "VARCHAR(" + bytes + ")";
        }
        if (t.startsWith("varchar")) {
            // Doris VARCHAR 按字节，中文需预留 *3；上限 65533，超出退化 STRING
            int bytes = lenOrDefault(t, 255) * 3;
            return bytes > 65533 ? "STRING" : "VARCHAR(" + bytes + ")";
        }
        // text/longtext/mediumtext/tinytext/blob/json/枚举/几何等统一 STRING
        return "STRING";
    }

    private int lenOrDefault(String type, int def) {
        Matcher m = LEN.matcher(type);
        if (m.find()) {
            return parseIntSafe(m.group(1), def);
        }
        return def;
    }

    /** 取时间类型精度 datetime(3)/timestamp(6) 的括号内数字，缺省返回 def。 */
    private int precisionOrDefault(String type, int def) {
        Matcher m = LEN.matcher(type);
        if (m.find()) {
            return parseIntSafe(m.group(1), def);
        }
        return def;
    }

    /** 安全解析整数：超大括号数字(如 varchar(99999999999))会溢出抛 NumberFormatException,兜底返回 def。 */
    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
