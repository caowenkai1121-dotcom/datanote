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
            regex("^/api/project/.*/releases/\\d+/(approve|reject|release|rollback)$", "project:approve"),
            regex("^/api/project/releases/\\d+/(approve|reject|release|rollback)$", "project:approve"),
            prefix("/api/ai/agent/approval", "assistant:approve"),
            regex("^/api/datamodel/change/\\d+/(approve|reject)$", "datamodel:approve"),
            prefix("/api/datamodel", "datamodel:edit"),
            // 密钥/敏感配置写: AI 模型密钥(embedding/chat key)、连接测试 须 settings:config(原仅 assistant:use 过低)
            prefix("/api/ai/store/config", "settings:config"),
            prefix("/api/ai/store/db-config", "settings:config"),   // 三库连接配置写: 与系统配置同权
            prefix("/api/ai/store/test", "settings:config"),        // 三库测连接: 含连接信息
            prefix("/api/ai/config", "settings:config"),
            prefix("/api/ai/test-connection", "settings:config"),
            prefix("/api/scheduler/backfill", "operations:backfill"),
            prefix("/api/baseline", "operations:baseline"),
            regex("^/api/sync-job/\\d+/(run|stop|online|offline)$", "dbsync:run"),
            regex("^/api/sync-job/bulk/(run|stop|online|offline)$", "dbsync:run"),
            prefix("/api/datax", "develop:run"),
            prefix("/api/cdc", "dbsync:run"),
            // --- 数据权限授权(读写均需 data:grant) ---
            prefix("/api/data-acl", "data:grant"),
            // --- RBAC / 系统配置 ---
            prefix("/api/rbac", "settings:user"),
            prefix("/api/system/config", "settings:config"),
            // --- 通用前缀(模块默认写权限) ---
            prefix("/api/script/changes", "develop:approve"),   // 脚本上线审批(SoD: 与提交者分权), 须先于 /api/script
            prefix("/api/script", "develop:edit"),
            prefix("/api/snippet", "develop:edit"),
            prefix("/api/datasource", "datasource:edit"),   // 数据源独立管控(写): 与 develop:edit 分权, 连接信息单独授权
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
            prefix("/api/metric", "metrics:edit"),
            prefix("/api/consumption", "metrics:edit"),
            prefix("/api/project", "project:manage"),
            prefix("/api/ai", "assistant:use")
    );

    /** 敏感 GET 规则(导出/全量代码/权限管理数据)。 */
    private static final List<Rule> SENSITIVE_GET_RULES = Arrays.asList(
            prefix("/api/gov/audit/export", "governance:audit"),
            regex("^/api/rbac/(users|roles)(/.*)?$", "settings:user"),
            // 全站权限点清单泄露系统 taxonomy, 仅配权限的管理员需要(原 GET 仅要求登录)
            prefix("/api/rbac/perms/catalog", "settings:user"),
            // 全脚本含完整 SQL(原 GET 仅要求登录, 任意用户可拉走所有脚本内容)
            prefix("/api/script/all-with-content", "develop:view"),
            // 脚本上线审批队列(列表/角标)含 payload SQL 快照+申请人, 仅审批人可见(须先于 /api/script)
            prefix("/api/script/changes", "develop:approve"),
            // 单脚本详情/版本/树含完整 SQL(原 GET 仅要求登录, 按自增 id 可枚举读他人脚本内容)
            prefix("/api/script", "develop:view"),
            // 数据模型审批队列含申请人/原因/模型变更信息, 仅审批人可见
            prefix("/api/datamodel/changes", "datamodel:approve"),
            // 系统配置(库/仓库连接 host/port/用户名/jdbc url, 密码已脱敏)读须与写同权
            prefix("/api/system/config", "settings:config"),
            // AI 配置(provider/baseUrl/model + apiKey 前 8 位)读须与写同权
            prefix("/api/ai/config", "settings:config"),
            // 三库连接配置读(含 url/掩码密钥)须与写同权
            prefix("/api/ai/store/db-config", "settings:config"),
            // 数据授权清单泄露资源可见范围, 读须 data:grant(与写同权)
            prefix("/api/data-acl", "data:grant"),
            // 各模块导出(审计已单列): 工单/指标/同步等导出按模块 view 保护
            prefix("/api/gov/health/issues/export", "governance:view"),
            prefix("/api/consumption/metric", "metrics:view"),
            // 同步样本预览/枚举表名: 直读源库明文数据与表名(原 GET 仅要求登录, 任意用户可拖库)
            regex("^/api/sync-job/\\d+/error-rows$", "dbsync:view"),
            prefix("/api/sync-job/preview", "dbsync:view"),
            prefix("/api/sync-job/match-tables", "dbsync:view"),
            // Doris/Hive 流式执行 GET 可跑任意 SQL/DDL(原 GET 仅要求登录, 绕过写权限)
            prefix("/api/doris/stream-execute", "catalog:edit"),
            prefix("/api/hive/stream-execute", "catalog:edit"),
            // 调度执行日志含 SQL/库表/连接信息(原 GET 仅要求登录, 任意用户按自增runId枚举读他人任务日志)
            prefix("/api/scheduler/run-log", "operations:schedule"),
            prefix("/api/scheduler/log-detail", "operations:schedule"),
            prefix("/api/scheduler/logs", "operations:schedule"),
            prefix("/api/task-execution", "operations:schedule"),
            // 数据源读: 列表/详情/库/表/列均暴露连接配置与源库结构(原 GET 仅要求登录), 须 datasource:view
            prefix("/api/datasource", "datasource:view")
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

    // ---------- 权限集+账号有效性缓存(30s, 账号/权限变更时主动全清立即生效) ----------

    private static final long CACHE_TTL_MS = 30_000L;

    private static final class CacheEntry {
        final Set<String> perms;
        final boolean active;   // 账号是否有效(表内须启用; 表外仅内存兜底引导账号)
        final long expireAt;
        CacheEntry(Set<String> perms, boolean active, long expireAt) { this.perms = perms; this.active = active; this.expireAt = expireAt; }
    }

    private final Map<String, CacheEntry> permCache = new ConcurrentHashMap<>();

    /** 停用/删除用户、改角色/权限后调用, 使变更立即生效(否则最迟 30s)。 */
    public void evictAll() {
        permCache.clear();
    }

    private CacheEntry entryOf(String username) {
        long now = System.currentTimeMillis();
        CacheEntry e = permCache.get(username);
        if (e != null && e.expireAt > now) return e;
        Set<String> perms;
        boolean active;
        try {
            com.datanote.platform.iam.model.DnUser u = rbacService.findByUsername(username);
            if (u != null) {
                // 表内用户: 须启用; 停用/被删后存量会话立刻失效(防离职/盗号账号继续用旧 session)
                active = u.getStatus() != null && u.getStatus() == 1;
                perms = active ? rbacService.getUserPerms(u.getId()) : Collections.emptySet();
            } else {
                // 不在表: 仅内存兜底引导账号有效且等同超级管理员;
                // 其余(如已被删除用户的旧会话)一律无效。
                active = username.equals(authProperties.getUsername());
                perms = active ? Collections.singleton("*") : Collections.emptySet();
            }
        } catch (Exception ex) {
            // 账号有效性判定 fail-closed: 查询异常时按未激活处理(踢会话), 不给已失效账号留读取窗口。
            // 异常结果不缓存, 避免一次抖动锁死整个 TTL 窗口。
            log.warn("查询用户权限失败, 按账号无效处理(fail-closed): {}", username, ex);
            return new CacheEntry(Collections.emptySet(), false, 0L);
        }
        CacheEntry ne = new CacheEntry(perms, active, now + CACHE_TTL_MS);
        permCache.put(username, ne);
        return ne;
    }

    /** 改密前的逃生口: 仅放行改密/登出/状态查询(及当前用户与权限查询), 其余一律拦。 */
    private static boolean isMustChangePwdAllowed(String path) {
        return path == null
                || path.startsWith("/api/auth/change-password")
                || path.startsWith("/api/auth/logout")
                || path.startsWith("/api/auth/status")
                || path.startsWith("/api/auth/login")
                || path.startsWith("/api/rbac/me");
    }

    /** 该会话是否因首登强制改密被拦(已在白名单内则放行, 避免无谓查库)。 */
    private boolean isMustChangePwdBlocked(String username, String path) {
        if (isMustChangePwdAllowed(path)) return false;
        return rbacService.mustChangePwd(username);
    }

    // ---------- 拦截 ----------

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!authProperties.isEnabled()) return true;   // 开放模式不鉴权

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) ? auth.getName() : null;
        if (username == null) return true;              // 未登录由 SecurityConfig 401, 此处不重复处理

        // 账号有效性: 停用/被删用户的存量会话立刻失效(含普通 GET), 防离职/盗号账号继续用旧 session
        CacheEntry entry = entryOf(username);
        if (!entry.active) {
            log.info("失效会话拒绝 user={} {} {}", username, request.getMethod(), request.getRequestURI());
            javax.servlet.http.HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(JSON.toJSONString(R.fail(R.CODE_UNAUTHORIZED, "账号已停用或删除, 请重新登录")));
            return false;
        }

        // 首登强制改密: 标记 mustChangePwd 的会话, 除改密/登出/状态外一律拒绝, 防绕过前端遮罩直调 API
        if (isMustChangePwdBlocked(username, request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(JSON.toJSONString(
                    R.fail(R.CODE_FORBIDDEN, "首次登录或密码已被重置, 请先修改密码后再操作")));
            return false;
        }

        String need = requiredPerm(request.getMethod(), request.getRequestURI());
        if (need == null) return true;                  // 只要求登录(SecurityConfig 已保证)

        if (RbacService.hasPermission(entry.perms, need)) return true;

        log.info("权限拒绝 user={} need={} {} {}", username, need, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Object> r = R.fail(R.CODE_FORBIDDEN, "无操作权限(需要 " + need + "), 请联系管理员分配");
        response.getWriter().write(JSON.toJSONString(r));
        return false;
    }
}
