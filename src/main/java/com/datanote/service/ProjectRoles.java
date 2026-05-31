package com.datanote.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目角色与权限矩阵（纯函数，可单测）。软控制：用于展示与成员/发布管理，不强拦截现有资产 API。
 * 权限点：project:edit / member:manage / asset:manage / release:submit / release:approve / run / read。
 */
public final class ProjectRoles {
    private ProjectRoles() {}

    public static final List<String> ROLES = Collections.unmodifiableList(
            Arrays.asList("OWNER", "ADMIN", "DEVELOPER", "OPS", "VIEWER"));

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> PERMS = new LinkedHashMap<>();
    static {
        LABELS.put("OWNER", "负责人");
        LABELS.put("ADMIN", "管理员");
        LABELS.put("DEVELOPER", "开发");
        LABELS.put("OPS", "运维");
        LABELS.put("VIEWER", "访客");
        PERMS.put("OWNER", perms("project:edit", "member:manage", "asset:manage", "release:submit", "release:approve", "run", "read"));
        PERMS.put("ADMIN", perms("project:edit", "member:manage", "asset:manage", "release:submit", "release:approve", "run", "read"));
        PERMS.put("DEVELOPER", perms("asset:manage", "release:submit", "run", "read"));
        PERMS.put("OPS", perms("run", "read"));
        PERMS.put("VIEWER", perms("read"));
    }

    private static Set<String> perms(String... ps) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(ps)));
    }

    public static boolean isValid(String role) {
        return role != null && PERMS.containsKey(role);
    }

    public static String label(String role) {
        return LABELS.getOrDefault(role, role);
    }

    public static Set<String> permsOf(String role) {
        return PERMS.getOrDefault(role, Collections.emptySet());
    }

    public static boolean can(String role, String perm) {
        return permsOf(role).contains(perm);
    }

    /** 角色权限矩阵（供前端展示）。 */
    public static List<Map<String, Object>> matrix() {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String r : ROLES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r);
            m.put("label", label(r));
            m.put("perms", new java.util.ArrayList<>(permsOf(r)));
            rows.add(m);
        }
        return rows;
    }
}
