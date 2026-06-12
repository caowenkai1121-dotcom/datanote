package com.datanote.platform.iam;

import com.alibaba.fastjson.JSON;
import com.datanote.common.model.R;
import com.datanote.platform.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 接口级权限拦截器(全站功能级鉴权)。
 * <p>策略(务实, 防误伤):
 * <ul>
 *   <li>开放模式(未设 DATANOTE_PASSWORD)不拦截 —— 与 SecurityConfig 行为一致;</li>
 *   <li>普通 GET 只要求登录(由 SecurityConfig 保证), 不卡 view 权限 —— 大量读接口被跨模块复用,
 *       模块可见性由前端菜单/路由守卫按 {@code 模块:view} 控制;</li>
 *   <li>写操作(POST/PUT/DELETE)按 URL 映射到 {@link PermCatalog} 权限点校验, 先特例后前缀;</li>
 *   <li>敏感 GET 特例单独要求权限(审计导出 / RBAC 用户与角色列表);</li>
 *   <li>{@code *} 权限放行一切; 未命中映射表的写操作只要求登录(如通知已读/登出)。</li>
 * </ul>
 * 权限集带 30s 内存缓存(改角色后最迟 30s 生效), 避免每次写请求查 4 张表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermInterceptor implements HandlerInterceptor {

    private final AuthProperties authProperties;
    private final RbacService rbacService;

    // ---------- URL → 权限点 映射 ----------

    /** 一条写权限规则: 路径谓词 + 所需权限点。 */
    private static final class Rule {
        final java.util.function.Predicate<String> match;
        final String perm;
        Rule(java.util.function.Predicate<String> match, String perm) { this.match = match; this.perm = perm; }
    }

    private static Rule prefix(String p, String perm) { return new Rule(path -> path.startsWith(p), perm); }
    private static Rule regex(String re, String perm) {
        final Pattern pt = Pattern.compile(re);
        return new Rule(path -> pt.matcher(path).matches(), perm);
    }

    /** 写操作规则表(顺序匹配, 特例在前)。 */
    private static final List<Rule> WRITE_RULES = Arrays.asList(
            // --- 特例: 审批/运行类动作权限 ---
            regex("^/api/mdm/approval/\\d+/(approve|reject)$", "mdm:approve"),
            regex("^/api/project/.*/releases/\\d+/approve$", "project:approve"),
            regex("^/api/project/releases/\\d+/approve$", "project:approve"),
            prefix("/api/ai/agent/approval", "assistant:approve"),
            prefix("/api/scheduler/backfill", "operations:backfill"),
            prefix("/api/baseline", "operations:baseline"),
            regex("^/api/sync-job/\\d+/(run|stop|online|offline)$", "dbsync:run"),
            regex("^/api/sync-job/bulk/(run|stop|online|offline)$", "dbsync:run"),
            prefix("/api/datax", "develop:run"),
            prefix("/api/cdc", "dbsync:run"),
            // --- RBAC / 系统配置 ---
            prefix("/api/rbac", "settings:user"),
            prefix("/api/group", "settings:user"),
            prefix("/api/system/config", "settings:config"),
            // --- 通用前缀(模块默认写权限) ---
            prefix("/api/script", "develop:edit"),
            prefix("/api/datasource", "develop:edit"),
            prefix("/api/scheduler", "operations:schedule"),
            prefix("/api/task-execution", "operations:schedule"),
            prefix("/api/metadata-center", "catalog:edit"),
            prefix("/api/metadata", "catalog:edit"),
            prefix("/api/subject", "catalog:edit"),
            prefix("/api/doris", "catalog:edit"),
            prefix("/api/hive", "catalog:edit"),
            prefix("/api/quality", "governance:quality"),
            prefix("/api/gov/health", "governance:issue"),
            prefix("/api/gov/standard", "governance:standard"),
            prefix("/api/gov/audit", "governance:audit"),
            prefix("/api/gov", "governance:manage"),
            prefix("/api/lineage", "governance:manage"),
            prefix("/api/mdm", "mdm:manage"),
            prefix("/api/sync-job", "dbsync:edit"),
            prefix("/api/sync-folder", "dbsync:edit"),
            prefix("/api/alert-config", "dbsync:edit"),
            prefix("/api/metric", "metrics:edit"),
            prefix("/api/consumption", "metrics:edit"),
            prefix("/api/project", "project:manage"),
            prefix("/api/ai", "assistant:use")
    );

    /** 敏感 GET 规则(导出/权限管理数据)。 */
    private static final List<Rule> SENSITIVE_GET_RULES = Arrays.asList(
            prefix("/api/gov/audit/export", "governance:audit"),
            regex("^/api/rbac/(users|roles)(/.*)?$", "settings:user")
    );

    /** 纯函数: 求该请求所需权限点(null=只要求登录)。包级可见便于单测。 */
    static String requiredPerm(String method, String path) {
        if (path == null || !path.startsWith("/api/")) return null;
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) {
            for (Rule r : SENSITIVE_GET_RULES) if (r.match.test(path)) return r.perm;
            return null;
        }
        for (Rule r : WRITE_RULES) if (r.match.test(path)) return r.perm;
        return null;
    }

    // ---------- 权限集缓存(30s) ----------

    private static final long CACHE_TTL_MS = 30_000L;

    private static final class CacheEntry {
        final Set<String> perms;
        final long expireAt;
        CacheEntry(Set<String> perms, long expireAt) { this.perms = perms; this.expireAt = expireAt; }
    }

    private final Map<String, CacheEntry> permCache = new ConcurrentHashMap<>();

    private Set<String> permsOf(String username) {
        long now = System.currentTimeMillis();
        CacheEntry e = permCache.get(username);
        if (e != null && e.expireAt > now) return e.perms;
        Set<String> perms;
        try {
            perms = rbacService.getUserPermsByUsername(username);
        } catch (Exception ex) {
            log.warn("查询用户权限失败, 按空权限处理: {}", username, ex);
            perms = Collections.emptySet();
        }
        // 内存兜底 admin(不在 dn_user 表): 配置用户名等同超级管理员
        if (perms.isEmpty() && username != null && username.equals(authProperties.getUsername())) {
            perms = Collections.singleton("*");
        }
        permCache.put(username, new CacheEntry(perms, now + CACHE_TTL_MS));
        return perms;
    }

    // ---------- 拦截 ----------

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!authProperties.isEnabled()) return true;   // 开放模式不鉴权

        String need = requiredPerm(request.getMethod(), request.getRequestURI());
        if (need == null) return true;                  // 只要求登录(SecurityConfig 已保证)

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) ? auth.getName() : null;
        if (username == null) return true;              // 未登录由 SecurityConfig 401, 此处不重复处理

        Set<String> perms = permsOf(username);
        if (RbacService.hasPermission(perms, need)) return true;

        log.info("权限拒绝 user={} need={} {} {}", username, need, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Object> r = R.fail(R.CODE_FORBIDDEN, "无操作权限(需要 " + need + "), 请联系管理员分配");
        response.getWriter().write(JSON.toJSONString(r));
        return false;
    }
}
