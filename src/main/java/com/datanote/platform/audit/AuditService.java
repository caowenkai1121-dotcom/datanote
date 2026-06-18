package com.datanote.platform.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.audit.mapper.DnAuditLogMapper;
import com.datanote.platform.audit.model.DnAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全局审计服务（M12）—— 入库（失败不阻断业务）+ 全局检索分页 + 统计 + CSV 导出。
 * 纯函数（shouldAudit/classify/csvCell/toCsv）静态可单测，不依赖 Spring/DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final DnAuditLogMapper auditMapper;

    /** 审计自身路径前缀：检索/导出/统计/登录留痕，不再二次审计（避免风暴与递归） */
    private static final String SELF_PREFIX = "/api/gov/audit";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    // ======================= 纯函数（可单测） =======================

    /**
     * 是否应审计：变更类（POST/PUT/DELETE）落在 /api/** 下；
     * 批4审计专项: GET 中敏感只读操作(导出/下载/数据预览/探查)也留痕——含审计自身导出
     * (谁导出了审计日志本身是最该留痕的动作)；其余 GET 一律不记（避免风暴）。
     * 审计自身变更类路径(login-record)仍排除避免递归。
     */
    public static boolean shouldAudit(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        if (!path.startsWith("/api/")) {
            return false;
        }
        if ("GET".equals(m)) {
            String p = path.toLowerCase(Locale.ROOT);
            // 敏感只读留痕(与 PermInterceptor.SENSITIVE_GET_RULES 对齐): 全量用户/角色、权限清单、含完整 SQL 的全部脚本
            boolean sensitiveRead = p.startsWith("/api/rbac/users") || p.startsWith("/api/rbac/roles")
                    || p.startsWith("/api/rbac/perms/catalog") || p.startsWith("/api/script/all-with-content");
            return p.contains("/export") || p.contains("/download")
                    || p.contains("/preview") || p.contains("/profile")
                    || sensitiveRead;
        }
        if (!("POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m))) {
            return false;
        }
        if (path.startsWith(SELF_PREFIX)) {
            return false;
        }
        // 登录由 AuthController 显式审计(LOGIN/LOGIN_FAIL/LOGIN_LOCKED 含真实用户与原因), filter 不重复记
        if (path.equals("/api/auth/login")) {
            return false;
        }
        return true;
    }

    /**
     * 动作类型归类：按路径关键字匹配，匹配不到归为 DATA_ACCESS。
     */
    public static String classify(String method, String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.contains("/login") || p.contains("/auth")) {
            return "LOGIN";
        }
        if (p.contains("/export") || p.contains("/download")) {
            return "EXPORT";
        }
        if (p.contains("/preview") || p.contains("/profile")) {
            return "DATA_PREVIEW";
        }
        if (p.contains("/rbac") || p.contains("/perm") || p.contains("/role") || p.contains("/user")) {
            return "PERM_CHANGE";
        }
        if (p.contains("/classification") || p.contains("/label") || p.contains("/confirm")) {
            return "LABEL_CHANGE";
        }
        if (p.contains("/quality") || p.contains("/standard") || p.contains("/rule")) {
            return "RULE_CHANGE";
        }
        if (p.contains("/metadata") || p.contains("/meta") || p.contains("/lineage")) {
            return "META_CHANGE";
        }
        return "DATA_ACCESS";
    }

    /**
     * CSV 单元格转义：含逗号/双引号/换行/回车时用双引号包裹，内部双引号翻倍。
     */
    public static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        // 防 CSV 公式注入：=、+、-、@、制表、回车开头的单元格前置单引号，避免 Excel/Sheets 当公式执行
        if (!s.isEmpty()) {
            char f = s.charAt(0);
            if (f == '=' || f == '+' || f == '-' || f == '@' || f == '\t' || f == '\r') {
                s = "'" + s;
            }
        }
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /**
     * 拼接 CSV（含中文表头）；空列表仅返回表头行。
     */
    public static String toCsv(List<DnAuditLog> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间,操作人,类型,方法,路径,IP,状态,详情\n");
        if (rows != null) {
            for (DnAuditLog r : rows) {
                sb.append(csvCell(r.getCreatedAt() == null ? "" : r.getCreatedAt().format(TS))).append(',')
                        .append(csvCell(r.getUserName())).append(',')
                        .append(csvCell(r.getActionType())).append(',')
                        .append(csvCell(r.getMethod())).append(',')
                        .append(csvCell(r.getPath())).append(',')
                        .append(csvCell(r.getIp())).append(',')
                        .append(csvCell(r.getStatus() == null ? "" : String.valueOf(r.getStatus()))).append(',')
                        .append(csvCell(r.getDetail())).append('\n');
            }
        }
        return sb.toString();
    }

    // ======================= 入库 =======================

    /**
     * 记录一条审计；任何异常仅告警，绝不抛出（审计失败不阻断业务）。
     */
    public void record(String userName, String actionType, String method,
                       String path, String ip, Integer status, String detail) {
        try {
            auditMapper.insert(buildLog(userName, actionType, method, path, ip, status, detail));
        } catch (Exception e) {
            log.warn("审计记录失败 user={} action={} path={}", userName, actionType, path, e);
        }
    }

    /** 组装审计实体并做字段截断(record/recordReturning 共用,避免两处重复) */
    private DnAuditLog buildLog(String userName, String actionType, String method,
                                String path, String ip, Integer status, String detail) {
        DnAuditLog a = new DnAuditLog();
        a.setUserName(userName == null || userName.isEmpty() ? "anonymous" : userName);
        a.setActionType(actionType);
        a.setMethod(method);
        a.setPath(path == null ? null : (path.length() > 255 ? path.substring(0, 255) : path));
        a.setIp(ip == null ? null : (ip.length() > 64 ? ip.substring(0, 64) : ip));
        a.setStatus(status);
        // P2: detail 入库前统一脱敏(防 SQL/连接串中的密码/token 明文落审计日志)
        String safeDetail = detail == null ? null : com.datanote.common.util.SecretRedactor.redact(detail);
        a.setDetail(safeDetail == null ? null : (safeDetail.length() > 60000 ? safeDetail.substring(0, 60000) : safeDetail));
        return a;
    }

    /**
     * 记录审计并返回自增主键(供 fail-closed 写前审计回读校验)。落库失败返 null(调用方据此拒绝执行写动作)。
     * 不改既有 record(零回归);本方法专供 AI Agent 写工具前置审计。
     */
    public Long recordReturning(String userName, String actionType, String method,
                                String path, String ip, Integer status, String detail) {
        try {
            DnAuditLog a = buildLog(userName, actionType, method, path, ip, status, detail);
            auditMapper.insert(a);
            return a.getId();
        } catch (Exception e) {
            log.warn("审计记录(returning)失败 user={} action={} path={}", userName, actionType, path, e);
            return null;
        }
    }

    /** 回读校验: 审计是否真落库。 */
    public boolean existsById(Long id) {
        if (id == null) return false;
        try {
            return auditMapper.selectById(id) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================= 检索 / 统计 =======================

    private QueryWrapper<DnAuditLog> buildWrapper(String from, String to, String actionType,
                                                  String userName, String path) {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        if (from != null && !from.isEmpty()) {
            qw.ge("created_at", from);
        }
        if (to != null && !to.isEmpty()) {
            qw.le("created_at", to);
        }
        if (actionType != null && !actionType.isEmpty()) {
            qw.eq("action_type", actionType);
        }
        if (userName != null && !userName.isEmpty()) {
            qw.like("user_name", userName);
        }
        if (path != null && !path.isEmpty()) {
            qw.like("path", path);
        }
        return qw;
    }

    /**
     * 分页检索（时间区间/类型/操作人/路径）。返回 total + list（手动 LIMIT 分页，无分页插件）。
     */
    public Map<String, Object> search(String from, String to, String actionType,
                                      String userName, String path, int page, int size) {
        int p = page < 1 ? 1 : (page > 10000 ? 10000 : page); // 限制翻页深度,防超大 offset 慢查询
        int s = size < 1 ? 20 : (size > 500 ? 500 : size);
        QueryWrapper<DnAuditLog> qw = buildWrapper(from, to, actionType, userName, path);
        Long total = auditMapper.selectCount(qw);
        long totalVal = total == null ? 0L : total; // selectCount 理论可返回 null,消 NPE
        qw.orderByDesc("created_at").last("LIMIT " + ((p - 1) * s) + "," + s);
        List<DnAuditLog> list = auditMapper.selectList(qw);
        Map<String, Object> r = new HashMap<>();
        r.put("total", totalVal);
        r.put("page", p);
        r.put("size", s);
        r.put("list", list == null ? Collections.emptyList() : list); // 结果判空,前端不再处理 null
        return r;
    }

    /**
     * 导出 CSV（同过滤条件，封顶 50000 行防内存爆）。
     */
    public String exportCsv(String from, String to, String actionType, String userName, String path) {
        QueryWrapper<DnAuditLog> qw = buildWrapper(from, to, actionType, userName, path);
        qw.orderByDesc("created_at").last("LIMIT 50000");
        List<DnAuditLog> rows = auditMapper.selectList(qw);
        return toCsv(rows == null ? Collections.emptyList() : rows); // 结果判空,toCsv 始终拿到非 null
    }

    /** 统计结果判空：selectMaps 理论可返回 null,统一兜底空列表,前端不再处理 null。 */
    private static List<Map<String, Object>> safeMaps(List<Map<String, Object>> maps) {
        return maps == null ? Collections.emptyList() : maps;
    }

    /** 实际出现过的动作类型(批4: 前端筛选下拉动态化, 不再写死枚举)。 */
    public List<String> distinctTypes() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("DISTINCT action_type").isNotNull("action_type").ne("action_type", "");
        List<Map<String, Object>> maps = safeMaps(auditMapper.selectMaps(qw));
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : maps) {
            Object v = m.get("action_type");
            if (v == null) v = m.get("actionType");
            if (v != null) out.add(String.valueOf(v));
        }
        Collections.sort(out);
        return out;
    }

    /** 按动作类型统计计数。 */
    public List<Map<String, Object>> statByType() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("action_type AS actionType", "COUNT(*) AS cnt")
                .groupBy("action_type").orderByDesc("cnt");
        return safeMaps(auditMapper.selectMaps(qw));
    }

    /** 按操作人统计计数（Top 50）。 */
    public List<Map<String, Object>> statByUser() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("user_name AS userName", "COUNT(*) AS cnt")
                .groupBy("user_name").orderByDesc("cnt").last("LIMIT 50");
        return safeMaps(auditMapper.selectMaps(qw));
    }

    /** 按访问路径统计计数（Top 20）。 */
    public List<Map<String, Object>> statByPath() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("path", "COUNT(*) AS cnt")
                .isNotNull("path").ne("path", "")
                .groupBy("path").orderByDesc("cnt").last("LIMIT 20");
        return safeMaps(auditMapper.selectMaps(qw));
    }

    /** 近 N 天审计量时序（按天计数）。 */
    public List<Map<String, Object>> trend(int days) {
        int d = days < 1 ? 7 : (days > 90 ? 90 : days);
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("DATE(created_at) AS day", "COUNT(*) AS cnt")
                .ge("created_at", LocalDateTime.now().minusDays(d - 1).toLocalDate().atStartOfDay())
                .groupBy("DATE(created_at)").orderByAsc("day");
        return safeMaps(auditMapper.selectMaps(qw));
    }
}
