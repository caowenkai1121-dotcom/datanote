package com.datanote.service;

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
        return issueMapper.selectList(qw);
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
            if (!("failed".equalsIgnoreCase(st) || "error".equalsIgnoreCase(st))) return;

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

    // ---- 以下为纯函数，便于单测 ----

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
}
