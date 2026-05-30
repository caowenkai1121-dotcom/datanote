package com.datanote.sync.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DS-M10：按表名正则批量筛选表（白名单 include + 黑名单 exclude，全匹配语义）。
 * include 空=全部；exclude 空=不排除。非法正则抛 PatternSyntaxException。
 */
public final class RegexTableMatcher {
    private RegexTableMatcher() {}

    public static List<String> match(List<String> tables, String include, String exclude) {
        Pattern inc = blank(include) ? null : Pattern.compile(include.trim());
        Pattern exc = blank(exclude) ? null : Pattern.compile(exclude.trim());
        List<String> out = new ArrayList<>();
        if (tables == null) return out;
        for (String t : tables) {
            if (t == null) continue;
            if (inc != null && !inc.matcher(t).matches()) continue;
            if (exc != null && exc.matcher(t).matches()) continue;
            out.add(t);
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
