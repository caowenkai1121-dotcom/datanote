package com.datanote.platform.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnAuditLogMapper;
import com.datanote.platform.audit.model.DnAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
     * 是否应审计：仅变更类（POST/PUT/DELETE）且落在 /api/** 下；
     * 排除审计自身路径避免递归；GET 等只读一律不记（避免风暴）。
     */
    public static boolean shouldAudit(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        if (!("POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m))) {
            return false;
        }
        if (!path.startsWith("/api/")) {
            return false;
        }
        if (path.startsWith(SELF_PREFIX)) {
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
            DnAuditLog a = new DnAuditLog();
            a.setUserName(userName == null || userName.isEmpty() ? "anonymous" : userName);
            a.setActionType(actionType);
            a.setMethod(method);
            a.setPath(path == null ? null : (path.length() > 255 ? path.substring(0, 255) : path));
            a.setIp(ip == null ? null : (ip.length() > 64 ? ip.substring(0, 64) : ip));
            a.setStatus(status);
            a.setDetail(detail == null ? null : (detail.length() > 60000 ? detail.substring(0, 60000) : detail));
            auditMapper.insert(a);
        } catch (Exception e) {
            log.warn("审计记录失败 user={} action={} path={}", userName, actionType, path, e);
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
        long total = auditMapper.selectCount(qw);
        qw.orderByDesc("created_at").last("LIMIT " + ((p - 1) * s) + "," + s);
        List<DnAuditLog> list = auditMapper.selectList(qw);
        Map<String, Object> r = new HashMap<>();
        r.put("total", total);
        r.put("page", p);
        r.put("size", s);
        r.put("list", list);
        return r;
    }

    /**
     * 导出 CSV（同过滤条件，封顶 50000 行防内存爆）。
     */
    public String exportCsv(String from, String to, String actionType, String userName, String path) {
        QueryWrapper<DnAuditLog> qw = buildWrapper(from, to, actionType, userName, path);
        qw.orderByDesc("created_at").last("LIMIT 50000");
        return toCsv(auditMapper.selectList(qw));
    }

    /** 按动作类型统计计数。 */
    public List<Map<String, Object>> statByType() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("action_type AS actionType", "COUNT(*) AS cnt")
                .groupBy("action_type").orderByDesc("cnt");
        return auditMapper.selectMaps(qw);
    }

    /** 按操作人统计计数（Top 50）。 */
    public List<Map<String, Object>> statByUser() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("user_name AS userName", "COUNT(*) AS cnt")
                .groupBy("user_name").orderByDesc("cnt").last("LIMIT 50");
        return auditMapper.selectMaps(qw);
    }

    /** 按访问路径统计计数（Top 20）。 */
    public List<Map<String, Object>> statByPath() {
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("path", "COUNT(*) AS cnt")
                .isNotNull("path").ne("path", "")
                .groupBy("path").orderByDesc("cnt").last("LIMIT 20");
        return auditMapper.selectMaps(qw);
    }

    /** 近 N 天审计量时序（按天计数）。 */
    public List<Map<String, Object>> trend(int days) {
        int d = days < 1 ? 7 : (days > 90 ? 90 : days);
        QueryWrapper<DnAuditLog> qw = new QueryWrapper<>();
        qw.select("DATE(created_at) AS day", "COUNT(*) AS cnt")
                .ge("created_at", LocalDateTime.now().minusDays(d - 1).toLocalDate().atStartOfDay())
                .groupBy("DATE(created_at)").orderByAsc("day");
        return auditMapper.selectMaps(qw);
    }
}
