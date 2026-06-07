package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnGovernanceIssueMapper;
import com.datanote.model.DnGovernanceIssue;
import com.datanote.model.DnQualityRule;
import com.datanote.model.DnQualityRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 治理工单服务 —— CRUD + 状态流转(OPEN→FIXING→RESOLVED→VERIFIED→CLOSED) + owner 路由 + 排行榜。
 * isLegalTransition 为纯函数（状态机，可单测）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueService {

    private final DnGovernanceIssueMapper issueMapper;

    /** 状态机：源态 -> 合法目标态集合 */
    private static final Map<String, Set<String>> FLOW = new HashMap<>();

    static {
        FLOW.put("OPEN", new HashSet<>(Arrays.asList("FIXING", "CLOSED")));
        FLOW.put("FIXING", new HashSet<>(Arrays.asList("RESOLVED", "OPEN")));
        FLOW.put("RESOLVED", new HashSet<>(Arrays.asList("VERIFIED", "FIXING")));
        FLOW.put("VERIFIED", new HashSet<>(Arrays.asList("CLOSED", "FIXING")));
        FLOW.put("CLOSED", new HashSet<>(Collections.singletonList("OPEN")));
    }

    // ========== 纯函数：状态机 ==========

    /** 判断从 from 流转到 to 是否合法 */
    public static boolean isLegalTransition(String from, String to) {
        if (from == null || to == null) return false;
        Set<String> next = FLOW.get(from);
        return next != null && next.contains(to);
    }

    /** 当前状态可流转到的目标态（供前端给按钮） */
    public static Set<String> nextStatuses(String from) {
        Set<String> s = FLOW.get(from);
        return s == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(s);
    }

    // ========== CRUD ==========

    /** 列表，可按 status/owner/dimension 过滤 */
    public List<DnGovernanceIssue> list(String status, String owner, String dimension) {
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        if (notBlank(status)) qw.eq("status", status);
        if (notBlank(owner)) qw.eq("owner", owner);
        if (notBlank(dimension)) qw.eq("dimension", dimension);
        qw.orderByDesc("updated_at");
        return enrichSla(issueMapper.selectList(qw));
    }

    /** 为工单列表注入 SLA 运营字段(存活小时 / 是否超期) */
    private List<DnGovernanceIssue> enrichSla(List<DnGovernanceIssue> rows) {
        LocalDateTime now = LocalDateTime.now();
        for (DnGovernanceIssue i : rows) {
            long age = ageHours(i.getCreatedAt(), now);
            i.setAgeHours(age);
            i.setOverdue(isOverdue(i.getStatus(), i.getSeverity(), age));
        }
        return rows;
    }

    public DnGovernanceIssue create(DnGovernanceIssue issue) {
        if (issue.getStatus() == null || issue.getStatus().isEmpty()) issue.setStatus("OPEN");
        if (issue.getSeverity() == null || issue.getSeverity().isEmpty()) issue.setSeverity("MEDIUM");
        issue.setId(null);
        issue.setCreatedAt(LocalDateTime.now());
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.insert(issue);
        return issue;
    }

    public void delete(Long id) {
        issueMapper.deleteById(id);
    }

    /** 状态流转：校验合法性，非法抛 IllegalStateException */
    public DnGovernanceIssue transition(Long id, String toStatus, String operator) {
        DnGovernanceIssue issue = issueMapper.selectById(id);
        if (issue == null) throw new IllegalStateException("工单不存在: " + id);
        if (!isLegalTransition(issue.getStatus(), toStatus)) {
            throw new IllegalStateException("非法流转: " + issue.getStatus() + " -> " + toStatus);
        }
        issue.setStatus(toStatus);
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        return issue;
    }

    /** 按 owner 路由：指派负责人 */
    public DnGovernanceIssue assign(Long id, String owner) {
        DnGovernanceIssue issue = issueMapper.selectById(id);
        if (issue == null) throw new IllegalStateException("工单不存在: " + id);
        issue.setOwner(owner);
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        return issue;
    }

    /** 排行榜：按 owner 统计 总数/未关单/已关单 */
    public List<Map<String, Object>> leaderboard() {
        List<DnGovernanceIssue> all = issueMapper.selectList(null);
        Map<String, long[]> agg = new LinkedHashMap<>(); // owner -> [total, open, closed]
        for (DnGovernanceIssue i : all) {
            String owner = notBlank(i.getOwner()) ? i.getOwner() : "未分配";
            long[] a = agg.computeIfAbsent(owner, k -> new long[3]);
            a[0]++;
            if ("CLOSED".equals(i.getStatus())) a[2]++;
            else a[1]++;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", e.getKey());
            m.put("total", e.getValue()[0]);
            m.put("open", e.getValue()[1]);
            m.put("closed", e.getValue()[2]);
            list.add(m);
        }
        list.sort((x, y) -> Long.compare((Long) y.get("open"), (Long) x.get("open")));
        return list;
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ========== 质量 → 工单 自动联动（治理闭环接点①） ==========

    /** 质量问题工单类型，与 dn_governance_issue.issue_type 约定一致 */
    static final String QUALITY_ISSUE_TYPE = "QUALITY";

    /**
     * 质量规则执行失败/异常时，自动生成（或刷新）治理工单 —— 打通"发现问题→督办整改"闭环。
     * 同一规则未关闭的工单只刷新不重复建单（去重）；本方法内部兜底，绝不抛异常影响质量执行主流程。
     */
    public void raiseQualityIssue(DnQualityRule rule, DnQualityRun run) {
        try {
            if (rule == null || run == null) return;
            String st = run.getRunStatus();
            if (!("failed".equalsIgnoreCase(st) || "error".equalsIgnoreCase(st))) {
                // 质量恢复达标 → 自动关闭未被人工介入的该规则工单(闭环另一半)
                closeQualityIssueOnRecovery(rule);
                return;
            }

            String objectRef = qualityIssueObjectRef(rule.getId());
            String title = qualityIssueTitle(rule, run);
            String desc = qualityIssueDescription(rule, run);
            String severity = qualitySeverity(rule, run);

            // 去重：同一规则存在未关闭(非 CLOSED)的质量工单则刷新，不重复建单
            QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
            qw.eq("issue_type", QUALITY_ISSUE_TYPE).eq("object_ref", objectRef)
              .ne("status", "CLOSED").orderByDesc("updated_at").last("LIMIT 1");
            DnGovernanceIssue existing = issueMapper.selectOne(qw);
            if (existing != null) {
                existing.setTitle(title);
                existing.setDescription(desc);
                existing.setSeverity(severity);
                existing.setUpdatedAt(LocalDateTime.now());
                issueMapper.updateById(existing);
                log.info("质量工单刷新(已存在未关闭工单) ruleId={} issueId={}", rule.getId(), existing.getId());
                return;
            }
            DnGovernanceIssue issue = new DnGovernanceIssue();
            issue.setIssueType(QUALITY_ISSUE_TYPE);
            issue.setDimension(notBlank(rule.getDimension()) ? rule.getDimension() : "数据质量");
            issue.setObjectRef(objectRef);
            issue.setTitle(title);
            issue.setDescription(desc);
            issue.setSeverity(severity);
            issue.setOwner(rule.getCreatedBy());
            issue.setStatus("OPEN");
            issue.setCreatedAt(LocalDateTime.now());
            issue.setUpdatedAt(LocalDateTime.now());
            issueMapper.insert(issue);
            log.info("质量失败自动生成治理工单 ruleId={} severity={} issueId={}", rule.getId(), severity, issue.getId());
        } catch (Exception e) {
            log.warn("质量工单自动生成失败(不影响质量执行) ruleId={}: {}",
                    rule != null ? rule.getId() : null, e.getMessage());
        }
    }

    /**
     * 质量恢复达标时，自动关闭仍处 OPEN（未被人工介入）的该规则质量工单，完成"发现→督办→恢复关单"闭环。
     * 已被人工流转（FIXING/RESOLVED/...）的工单不自动关，尊重人工整改流程。
     */
    private void closeQualityIssueOnRecovery(DnQualityRule rule) {
        if (rule == null || rule.getId() == null) return;
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        qw.eq("issue_type", QUALITY_ISSUE_TYPE).eq("object_ref", qualityIssueObjectRef(rule.getId()))
          .ne("status", "CLOSED").orderByDesc("updated_at").last("LIMIT 1");
        DnGovernanceIssue existing = issueMapper.selectOne(qw);
        if (existing == null || !shouldAutoCloseOnRecovery(existing.getStatus())) return;
        existing.setStatus("CLOSED");
        String note = "\n[自动关单] 规则已恢复达标，系统自动关闭于 " + LocalDateTime.now();
        existing.setDescription((existing.getDescription() == null ? "" : existing.getDescription()) + note);
        existing.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(existing);
        log.info("质量恢复自动关单 ruleId={} issueId={}", rule.getId(), existing.getId());
    }

    // ---- 以下为纯函数，便于单测 ----

    /** 仅自动关闭仍为 OPEN（无人工流转）的工单，尊重人工进行中的整改流程 */
    static boolean shouldAutoCloseOnRecovery(String status) {
        return "OPEN".equals(status);
    }

    /** 工单对象引用键：以规则维度去重（同一规则一条未关闭工单） */
    static String qualityIssueObjectRef(Long ruleId) {
        return "qrule:" + ruleId;
    }

    /** 规范化严重度，仅接受 HIGH/MEDIUM/LOW，否则返回 null */
    static String normalizeSeverity(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase();
        return ("HIGH".equals(u) || "MEDIUM".equals(u) || "LOW".equals(u)) ? u : null;
    }

    /**
     * 工单严重度判定：执行异常或强阻断规则=HIGH；规则已声明则用声明值；否则按通过率推断。
     */
    static String qualitySeverity(DnQualityRule rule, DnQualityRun run) {
        if (run != null && "error".equalsIgnoreCase(run.getRunStatus())) return "HIGH";
        boolean strong = rule != null && rule.getBlockDownstream() != null && rule.getBlockDownstream() == 1;
        if (strong) return "HIGH";
        String declared = normalizeSeverity(rule == null ? null : rule.getSeverity());
        if (declared != null) return declared;
        java.math.BigDecimal rate = run == null ? null : run.getPassRate();
        if (rate == null) return "MEDIUM";
        double v = rate.doubleValue();
        if (v < 50) return "HIGH";
        if (v < 90) return "MEDIUM";
        return "LOW";
    }

    static String qualityIssueTitle(DnQualityRule rule, DnQualityRun run) {
        String name = (rule != null && notBlankStatic(rule.getRuleName()))
                ? rule.getRuleName() : ("#" + (rule == null ? "?" : rule.getId()));
        String t;
        if (run != null && "error".equalsIgnoreCase(run.getRunStatus())) {
            t = "[质量异常] 规则「" + name + "」执行失败";
        } else {
            String rate = (run != null && run.getPassRate() != null) ? run.getPassRate().toPlainString() : "?";
            String th = (rule != null && rule.getPassThreshold() != null) ? rule.getPassThreshold().toPlainString() : "100";
            t = "[质量未达标] 规则「" + name + "」通过率 " + rate + "% < 阈值 " + th + "%";
        }
        return t.length() <= 200 ? t : t.substring(0, 200);
    }

    static String qualityIssueDescription(DnQualityRule rule, DnQualityRun run) {
        StringBuilder sb = new StringBuilder();
        String db = nvl(rule.getDatabaseName()), tbl = nvl(rule.getTableName()), col = nvl(rule.getColumnName());
        sb.append("对象: ").append(db).append(".").append(tbl);
        if (!col.isEmpty()) sb.append(".").append(col);
        sb.append("\n规则类型: ").append(nvl(rule.getRuleType()));
        if (run != null && "error".equalsIgnoreCase(run.getRunStatus())) {
            sb.append("\n执行异常: ").append(nvl(run.getErrorMsg()));
        } else if (run != null) {
            String rate = run.getPassRate() != null ? run.getPassRate().toPlainString() : "?";
            String th = rule.getPassThreshold() != null ? rule.getPassThreshold().toPlainString() : "100";
            sb.append("\n通过率: ").append(rate).append("% (阈值 ").append(th).append("%)");
            sb.append("\n失败行数: ").append(run.getFailCount() == null ? 0 : run.getFailCount())
              .append(" / 共 ").append(run.getTotalCount() == null ? 0 : run.getTotalCount()).append(" 行");
        }
        if (run != null && run.getStartedAt() != null) sb.append("\n执行时间: ").append(run.getStartedAt());
        sb.append("\n来源: 质量规则 #").append(rule.getId()).append(" 失败自动生成");
        return sb.toString();
    }

    private static boolean notBlankStatic(String s) { return s != null && !s.trim().isEmpty(); }

    private static String nvl(String s) { return s == null ? "" : s; }

    // ========== R4 工单运营中心：SLA / 统计 / 趋势 / 导出 / 反查 ==========

    /** SLA 阈值(小时)：HIGH 24 / MEDIUM 72 / LOW 168 */
    static long slaHours(String severity) {
        if ("HIGH".equalsIgnoreCase(severity)) return 24L;
        if ("LOW".equalsIgnoreCase(severity)) return 168L;
        return 72L;
    }

    /** 仅未闭环(OPEN/FIXING)且存活≥SLA 视为超期 */
    static boolean isOverdue(String status, String severity, long ageHours) {
        if (!("OPEN".equals(status) || "FIXING".equals(status))) return false;
        return ageHours >= slaHours(severity);
    }

    private static long ageHours(LocalDateTime from, LocalDateTime now) {
        if (from == null) return 0L;
        long h = java.time.Duration.between(from, now).toHours();
        return h < 0 ? 0L : h;
    }

    /** CSV 单元格：防注入(=,+,-,@ 开头前缀单引号) + 转义逗号/引号/换行 */
    static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 工单运营统计：多维分布 + 未关/超期 + 解决率 + 平均处理时长 + 近7日关闭数 */
    public Map<String, Object> stats() {
        List<DnGovernanceIssue> all = issueMapper.selectList(null);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> byStatus = new LinkedHashMap<>(), bySeverity = new LinkedHashMap<>(),
                byDimension = new LinkedHashMap<>(), byType = new LinkedHashMap<>();
        int open = 0, overdue = 0, resolvedCnt = 0, closed7d = 0;
        long resolveHoursSum = 0;
        for (DnGovernanceIssue i : all) {
            byStatus.merge(nvl(i.getStatus()), 1, Integer::sum);
            bySeverity.merge(nvl(i.getSeverity()), 1, Integer::sum);
            byDimension.merge(i.getDimension() == null ? "未分类" : i.getDimension(), 1, Integer::sum);
            byType.merge(nvl(i.getIssueType()), 1, Integer::sum);
            boolean unresolved = "OPEN".equals(i.getStatus()) || "FIXING".equals(i.getStatus());
            if (unresolved) open++;
            long age = ageHours(i.getCreatedAt(), now);
            if (isOverdue(i.getStatus(), i.getSeverity(), age)) overdue++;
            if ("CLOSED".equals(i.getStatus())) {
                resolvedCnt++;
                resolveHoursSum += ageHours(i.getCreatedAt(), i.getUpdatedAt() == null ? now : i.getUpdatedAt());
                if (i.getUpdatedAt() != null && i.getUpdatedAt().isAfter(now.minusDays(7))) closed7d++;
            }
        }
        int total = all.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("open", open);
        out.put("overdue", overdue);
        out.put("closed", resolvedCnt);
        out.put("closed7d", closed7d);
        out.put("resolveRate", total == 0 ? 0 : Math.round(resolvedCnt * 1000.0 / total) / 10.0);
        out.put("avgResolveHours", resolvedCnt == 0 ? 0 : resolveHoursSum / resolvedCnt);
        out.put("byStatus", byStatus);
        out.put("bySeverity", bySeverity);
        out.put("byDimension", byDimension);
        out.put("byType", byType);
        return out;
    }

    /** 工单趋势：近 days 天每日新建数 / 关闭数(关闭以 CLOSED 工单 updated_at 计) */
    public List<Map<String, Object>> trend(int days) {
        int d = days <= 0 ? 30 : Math.min(days, 365);
        List<DnGovernanceIssue> all = issueMapper.selectList(null);
        Map<java.time.LocalDate, int[]> bucket = new LinkedHashMap<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int k = d - 1; k >= 0; k--) bucket.put(today.minusDays(k), new int[2]); // [created, closed]
        for (DnGovernanceIssue i : all) {
            if (i.getCreatedAt() != null) {
                int[] b = bucket.get(i.getCreatedAt().toLocalDate());
                if (b != null) b[0]++;
            }
            if ("CLOSED".equals(i.getStatus()) && i.getUpdatedAt() != null) {
                int[] b = bucket.get(i.getUpdatedAt().toLocalDate());
                if (b != null) b[1]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        bucket.forEach((date, cnt) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", date.toString());
            m.put("created", cnt[0]);
            m.put("closed", cnt[1]);
            out.add(m);
        });
        return out;
    }

    /** 导出工单为 CSV(含 SLA 字段, 防注入) */
    public String exportCsv(String status, String owner, String dimension) {
        List<DnGovernanceIssue> rows = list(status, owner, dimension);
        StringBuilder sb = new StringBuilder();
        sb.append("ID,标题,类型,维度,级别,负责人,状态,超期,存活小时,对象,创建时间,更新时间\n");
        for (DnGovernanceIssue i : rows) {
            sb.append(csvCell(i.getId())).append(',')
              .append(csvCell(i.getTitle())).append(',')
              .append(csvCell(i.getIssueType())).append(',')
              .append(csvCell(i.getDimension())).append(',')
              .append(csvCell(i.getSeverity())).append(',')
              .append(csvCell(i.getOwner())).append(',')
              .append(csvCell(i.getStatus())).append(',')
              .append(csvCell(Boolean.TRUE.equals(i.getOverdue()) ? "是" : "否")).append(',')
              .append(csvCell(i.getAgeHours())).append(',')
              .append(csvCell(i.getObjectRef())).append(',')
              .append(csvCell(i.getCreatedAt())).append(',')
              .append(csvCell(i.getUpdatedAt())).append('\n');
        }
        return sb.toString();
    }

    /** 按质量规则反查其关联工单(objectRef=qrule:{ruleId}) */
    public List<DnGovernanceIssue> listByQualityRule(Long ruleId) {
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        qw.eq("issue_type", QUALITY_ISSUE_TYPE).eq("object_ref", qualityIssueObjectRef(ruleId))
          .orderByDesc("updated_at");
        return enrichSla(issueMapper.selectList(qw));
    }
}
