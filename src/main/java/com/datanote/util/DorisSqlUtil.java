package com.datanote.util;

import com.datanote.model.ColumnInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DorisSqlUtil {

    private DorisSqlUtil() {
    }

    public static List<String> toDorisColumnNames(List<ColumnInfo> columns) {
        List<String> names = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        if (columns == null) {
            return names;
        }
        for (ColumnInfo column : columns) {
            String base = toDorisColumnName(column != null ? column.getName() : null);
            int count = seen.containsKey(base) ? seen.get(base) + 1 : 1;
            seen.put(base, count);
            names.add(count == 1 ? base : base + "_" + count);
        }
        return names;
    }

    public static String toDorisColumnName(String rawName) {
        String name = rawName == null ? "" : rawName.trim().toLowerCase();
        name = name.replaceAll("[^a-z0-9_]+", "_");
        name = name.replaceAll("_+", "_");
        name = name.replaceAll("^_+", "").replaceAll("_+$", "");
        if (name.isEmpty()) {
            name = "col";
        }
        if (Character.isDigit(name.charAt(0))) {
            name = "c_" + name;
        }
        return name;
    }

    public static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifier cannot be empty");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    public static String quoteQualified(String database, String table) {
        return quoteIdentifier(database) + "." + quoteIdentifier(table);
    }

    public static String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
