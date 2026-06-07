package com.datanote.domain.project;

/** 项目编码规范化（纯函数，可单测）：取名称中的 ASCII 字母数字转大写，空则回退 PROJ。 */
public final class ProjectCodeGenerator {
    private ProjectCodeGenerator() {}

    public static String slug(String name) {
        if (name == null) return "PROJ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 24; i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') sb.append(c);
            else if (c >= 'a' && c <= 'z') sb.append((char) (c - 32));
            else if (c >= '0' && c <= '9') sb.append(c);
            else if (c == '_' || c == '-') sb.append('_');
        }
        // 去掉首尾下划线
        String s = sb.toString().replaceAll("^_+", "").replaceAll("_+$", "");
        return s.isEmpty() ? "PROJ" : s;
    }
}
