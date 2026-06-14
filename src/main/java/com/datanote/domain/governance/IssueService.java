package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.governance.mapper.DnGovernanceIssueMapper;
import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import com.datanote.domain.governance.model.DnGovernanceIssue;
import com.datanote.domain.governance.model.DnQualityRule;
import com.datanote.domain.governance.model.DnQualityRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    private final DnQualityRuleMapper qualityRuleMapper;
    private final com.datanote.platform.notify.NotificationService notificationService;   // 全站#25 工单通知
    // @Lazy 打破环: QualityService 依赖 IssueService(失败建单), 此处反向惰性注入用于"解决/关闭前复检规则"
    @Lazy
    @Autowired
    private QualityService qualityService;

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
        if (rows == null || rows.isEmpty()) return rows == null ? new ArrayList<>() : rows;
        LocalDateTime now = LocalDateTime.now();
        for (DnGovernanceIssue i : rows) {
            if (i == null) continue;
            long age = ageHours(i.getCreatedAt(), now);
            i.setAgeHours(age);
            i.setOverdue(isOverdue(i.getStatus(), i.getSeverity(), age));
        }
        return rows;
    }

    public DnGovernanceIssue create(DnGovernanceIssue issue) {
        if (issue == null) throw new BusinessException("工单内容不能为空");
        if (!notBlank(issue.getTitle())) throw new BusinessException("工单标题不能为空");
        if (!notBlank(issue.getIssueType())) throw new BusinessException("工单类型不能为空");
        if (issue.getStatus() == null || issue.getStatus().isEmpty()) issue.setStatus("OPEN");
        if (issue.getSeverity() == null || issue.getSeverity().isEmpty()) issue.setSeverity("MEDIUM");
        issue.setId(null);
        issue.setCreatedAt(LocalDateTime.now());
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.insert(issue);
        return issue;
    }

    public void delete(Long id) {
        if (id == null) throw new BusinessException("工单ID不能为空");
        issueMapper.deleteById(id);
    }

    /** 状态流转：校验合法性，非法抛 IllegalStateException(由 controller 捕获转 R.fail) */
    public DnGovernanceIssue transition(Long id, String toStatus, String operator) {
        if (id == null) throw new IllegalStateException("工单ID不能为空");
        if (toStatus == null || toStatus.trim().isEmpty()) throw new IllegalStateException("目标状态不能为空");
        DnGovernanceIssue issue = issueMapper.selectById(id);
        if (issue == null) throw new IllegalStateException("工单不存在: " + id);
        if (!isLegalTransition(issue.getStatus(), toStatus)) {
            throw new IllegalStateException("非法流转: " + issue.getStatus() + " -> " + toStatus);
        }
        // 质量工单"已解决/关闭"前【复检规则】: 重跑规则, 数据仍未达标则拦下——防"假关闭"(工单关了但数据还坏)
        if (QUALITY_ISSUE_TYPE.equals(issue.getIssueType()) && ("RESOLVED".equals(toStatus) || "CLOSED".equals(toStatus))) {
            String block = reverifyQualityRule(issue, toStatus);
            if (block != null) throw new IllegalStateException(block);
        }
        issue.setStatus(toStatus);
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        return issue;
    }

    /**
     * 复检质量工单关联的规则(objectRef=qrule:{id}): 重跑该规则, 若数据仍未达标(failed)则返回拦截原因。
     * 复检无法执行(规则已删/数据源不通=error)或非规则关联工单则放行(不因基础设施问题困住整改)。
     */
    private String reverifyQualityRule(DnGovernanceIssue issue, String toStatus) {
        Long ruleId = parseQRuleId(issue.getObjectRef());
        if (ruleId == null) return null;                 // 非规则关联工单, 不拦
        DnQualityRule rule = qualityRuleMapper.selectById(ruleId);
        if (rule == null) return null;                   // 规则已删, 不拦
        DnQualityRun run;
        try { run = qualityService.executeRule(rule); }  // 复跑校验
        catch (Exception e) {
            log.warn("工单复检规则执行异常 ruleId={}: {}", ruleId, e.getMessage());
            return null;                                 // 无法复检(数据源不通等)→放行, 不困住用户
        }
        if (run != null && "failed".equalsIgnoreCase(run.getRunStatus())) {
            String rate = run.getPassRate() != null ? run.getPassRate().toPlainString() : "?";
            String th = rule.getPassThreshold() != null ? rule.getPassThreshold().toPlainString() : "100";
            String label = "RESOLVED".equals(toStatus) ? "已解决" : "关闭";
            return "数据复检未通过: 规则「" + nvl(rule.getRuleName()) + "」当前通过率 " + rate + "% < 阈值 " + th
                    + "%, 不能标记为" + label + "; 请先修复数据再操作。";
        }
        return null; // 复检通过(或 error)→放行
    }

    /** 解析工单对象引用 qrule:{id} → ruleId; 非该前缀返回 null。 */
    static Long parseQRuleId(String objectRef) {
        if (objectRef == null) return null;
        String p = objectRef.trim();
        if (!p.startsWith("qrule:")) return null;
        try { return Long.parseLong(p.substring("qrule:".length()).trim()); } catch (Exception e) { return null; }
    }

    /** 按 owner 路由：指派负责人 */
    public DnGovernanceIssue assign(Long id, String owner) {
        if (id == null) throw new IllegalStateException("工单ID不能为空");
        if (owner == null || owner.trim().isEmpty()) throw new IllegalStateException("负责人不能为空");
        DnGovernanceIssue issue = issueMapper.selectById(id);
        if (issue == null) throw new IllegalStateException("工单不存在: " + id);
        issue.setOwner(owner);
        issue.setUpdatedAt(LocalDateTime.now());
        issueMapper.updateById(issue);
        // 全站#25: 指派即时通知被指派人, 铃铛深链工单详情
        notificationService.notify(owner.trim(), "QUALITY_ISSUE",
                "治理工单已指派给你: " + (issue.getTitle() == null ? ("#" + issue.getId()) : issue.getTitle()),
                "govissue", issue.getId(), null);
        return issue;
    }

    /** 排行榜：按 owner 统计 总数/未关单/已关单 */
    public List<Map<String, Object>> leaderboard() {
        // DB 侧 group by owner,status 聚合, 避免全表拉进内存
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        qw.select("owner", "status", "count(*) AS cnt").groupBy("owner", "status");
        List<Map<String, Object>> rows = issueMapper.selectMaps(qw);
        if (rows == null) rows = Collections.emptyList();
        Map<String, long[]> agg = new LinkedHashMap<>(); // owner -> [total, open, closed]
        for (Map<String, Object> r : rows) {
            if (r == null) continue;
            Object o = r.get("owner");
            String ownerStr = o == null ? null : String.valueOf(o);
            String owner = notBlank(ownerStr) ? ownerStr : "未分配";
            long cnt = ((Number) r.get("cnt")).longValue();
            long[] a = agg.computeIfAbsent(owner, k -> new long[3]);
            a[0] += cnt;
            if ("CLOSED".equals(r.get("status"))) a[2] += cnt;
            else a[1] += cnt;
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
            // 全站#25: 新建工单通知规则负责人(无则 admin), 刷新已有工单不重复扰动
            String receiver = notBlank(issue.getOwner()) ? issue.getOwner() : "admin";
            notificationService.notify(receiver, "QUALITY_ISSUE",
                    "质量规则未达标已建工单: " + (rule.getRuleName() == null ? ("规则#" + rule.getId()) : rule.getRuleName()),
                    "govissue", issue.getId(), null);
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

    /**
     * 删除质量规则时, 关闭其全部未关闭工单(规则已不存在、无法再复检), 防僵尸工单堆积。
     * 关单而非删单, 保留治理历史。返回关单数。
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int closeIssuesForDeletedRule(Long ruleId) {
        if (ruleId == null) return 0;
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        qw.eq("issue_type", QUALITY_ISSUE_TYPE).eq("object_ref", qualityIssueObjectRef(ruleId)).ne("status", "CLOSED");
        List<DnGovernanceIssue> open = issueMapper.selectList(qw);
        if (open == null || open.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        for (DnGovernanceIssue i : open) {
            i.setStatus("CLOSED");
            i.setDescription((i.getDescription() == null ? "" : i.getDescription())
                    + "\n[自动关单] 关联质量规则已删除于 " + now);
            i.setUpdatedAt(now);
            issueMapper.updateById(i);
        }
        log.info("质量规则删除自动关单 ruleId={} count={}", ruleId, open.size());
        return open.size();
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

    /** 规范化严重度，接受 HIGH/MEDIUM/LOW 及质量规则小写词表 error/warning/info（映射到工单大写词表），否则返回 null */
    static String normalizeSeverity(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase();
        if ("ERROR".equals(u)) return "HIGH";
        if ("WARNING".equals(u)) return "MEDIUM";
        if ("INFO".equals(u)) return "LOW";
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
        if (rule == null) return "对象: ?\n来源: 质量规则失败自动生成";
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
        return Math.max(0L, h);
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
        if (all == null) all = Collections.emptyList();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> byStatus = new LinkedHashMap<>(), bySeverity = new LinkedHashMap<>(),
                byDimension = new LinkedHashMap<>(), byType = new LinkedHashMap<>();
        int open = 0, overdue = 0, resolvedCnt = 0, closed7d = 0;
        long resolveHoursSum = 0;
        for (DnGovernanceIssue i : all) {
            if (i == null) continue;
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
        java.time.LocalDate today = java.time.LocalDate.now();
        // 仅拉取窗口内有贡献的行: created_at 在窗口内, 或 已关闭(CLOSED)且 updated_at 在窗口内
        LocalDateTime windowStart = today.minusDays(d - 1).atStartOfDay();
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        qw.ge("created_at", windowStart)
          .or(w -> w.eq("status", "CLOSED").ge("updated_at", windowStart));
        List<DnGovernanceIssue> all = issueMapper.selectList(qw);
        if (all == null) all = Collections.emptyList();
        Map<java.time.LocalDate, int[]> bucket = new LinkedHashMap<>();
        for (int k = d - 1; k >= 0; k--) bucket.put(today.minusDays(k), new int[2]); // [created, closed]
        for (DnGovernanceIssue i : all) {
            if (i == null) continue;
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
            if (i == null) continue;
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
        if (ruleId == null) throw new BusinessException("质量规则ID不能为空");
        QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
        qw.eq("issue_type", QUALITY_ISSUE_TYPE).eq("object_ref", qualityIssueObjectRef(ruleId))
          .orderByDesc("updated_at");
        return enrichSla(issueMapper.selectList(qw));
    }
}
