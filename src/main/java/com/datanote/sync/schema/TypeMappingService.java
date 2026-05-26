package com.datanote.sync.schema;

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

        if (t.startsWith("tinyint")) return "TINYINT";
        if (t.startsWith("smallint")) return "SMALLINT";
        if (t.startsWith("mediumint")) return "INT";
        if (t.startsWith("bigint")) return "BIGINT";
        if (t.startsWith("int") || t.startsWith("integer")) return "INT";
        if (t.startsWith("float")) return "FLOAT";
        if (t.startsWith("double")) return "DOUBLE";
        if (t.startsWith("decimal") || t.startsWith("numeric")) {
            Matcher m = DECIMAL.matcher(t);
            if (m.find()) {
                return "DECIMAL(" + m.group(1) + "," + m.group(2) + ")";
            }
            return "DECIMAL(10,0)";
        }
        if (t.startsWith("datetime") || t.startsWith("timestamp")) return "DATETIME";
        if (t.startsWith("date")) return "DATE";
        if (t.startsWith("char")) {
            return "CHAR(" + lenOrDefault(t, 1) + ")";
        }
        if (t.startsWith("varchar")) {
            // Doris VARCHAR 按字节，中文需预留，长度 *3
            return "VARCHAR(" + (lenOrDefault(t, 255) * 3) + ")";
        }
        // text/longtext/mediumtext/tinytext/blob/json/枚举/几何等统一 STRING
        return "STRING";
    }

    private int lenOrDefault(String type, int def) {
        Matcher m = LEN.matcher(type);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return def;
    }
}
