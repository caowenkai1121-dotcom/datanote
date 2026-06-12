package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.consumption.mapper.DnMetricAlertRuleMapper;
import com.datanote.domain.consumption.model.DnMetricAlertRule;
import com.datanote.domain.governance.IssueService;
import com.datanote.domain.governance.mapper.DnGovernanceIssueMapper;
import com.datanote.domain.governance.model.DnGovernanceIssue;
import com.datanote.domain.governance.model.DnMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标预警服务（闭合 消费→治理 环）：指标计算出值后，按阈值规则判定越界，越界则自动生成治理工单(去重)。
 * 复用 governance 的 {@link IssueService}/工单表，与质量→工单同闭环风格。全程兜底不抛，不影响指标计算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricAlertService {

    private final DnMetricAlertRuleMapper ruleMapper;
    private final DnGovernanceIssueMapper issueMapper;
    private final IssueService issueService;
    private final com.datanote.platform.notify.NotificationService notificationService;   // IV-1 旁路通知(fail-safe)

    static final String ISSUE_TYPE = "METRIC";

    /**
     * 指标计算出数值后调用：逐条启用规则判定，越界自动建/刷工单。
     * 此方法为指标计算主流程的旁路钩子，按设计全程兜底不抛、不影响主流程；
     * 入参非法或单条规则处理失败均记日志并跳过，保证其余规则照常判定（每条规则相互独立）。
     */
    public void checkAndAlert(DnMetric metric, BigDecimal value) {
        // 入参守卫：旁路钩子不抛异常，非法入参记日志后静默返回(行为契约:不影响主流程)
        if (metric == null || value == null) {
            log.debug("指标预警跳过：metric 或 value 为空 metricId={}", metric == null ? null : metric.getId());
            return;
        }
        Long metricId = metric.getId();
        if (metricId == null) {
            log.warn("指标预警跳过：metric.id 为空，无法按 metric_id 查询规则");
            return;
        }
        try {
            List<DnMetricAlertRule> rules = ruleMapper.selectList(
                    new QueryWrapper<DnMetricAlertRule>().eq("metric_id", metricId).eq("enabled", 1));
            if (rules == null || rules.isEmpty()) return; // 空安全：无启用规则直接返回
            DnMetricAlertRule worst = null;   // 越界规则中级别最重的一条(指标级去重: 多规则合并一单)
            for (DnMetricAlertRule r : rules) {
                if (r == null) continue;
                try {
                    if (isBreach(r.getOp(), value, r.getThresholdMin(), r.getThresholdMax())) {
                        if (worst == null || severityRank(r.getSeverity()) > severityRank(worst.getSeverity())) worst = r;
                    }
                } catch (Exception e) {
                    // 单条规则失败不拖累其余规则，记录上下文后继续
                    log.warn("指标预警单条规则处理失败 metricId={} ruleId={}: {}",
                            metricId, r.getId(), e.getMessage());
                }
            }
            if (worst != null) raiseIssue(metric, worst, value);
            else closeRecovered(metric, value);   // 全部启用规则均未越界 → 指标已恢复, 自动关 OPEN 单
        } catch (Exception e) {
            log.warn("指标预警判定失败(不影响计算) metricId={}: {}", metricId, e.getMessage());
        }
    }

    private void raiseIssue(DnMetric m, DnMetricAlertRule r, BigDecimal value) {
        if (m == null || r == null || value == null) return; // 空安全:理论不达,防御性兜底
        // objectRef 用指标ID(对齐 gov-health 手工建单 metric:{metricId} 全局约定, 下钻可达);
        // 去重粒度同步为指标级: 同指标多规则越界合并为一单, 防刷单
        String objectRef = "metric:" + m.getId();
        String valStr = value.toPlainString();
        // 指标名优先取 metricName，缺失退化到 metricCode，均空则占位，避免标题出现 "null"
        String metricLabel = notBlank(m.getMetricName()) ? m.getMetricName()
                : (notBlank(m.getMetricCode()) ? m.getMetricCode() : ("#" + m.getId()));
        String title = trunc("[指标预警] " + metricLabel + " 当前值 " + valStr + " "
                + breachDesc(r.getOp(), r.getThresholdMin(), r.getThresholdMax()), 200);
        String sev = normalizeSeverity(r.getSeverity());
        String desc = "指标 " + (m.getMetricCode() == null ? ("#" + m.getId()) : m.getMetricCode())
                + " 触发预警规则 #" + r.getId() + "，当前值 " + valStr;
        // 去重: 同指标存量单(含已闭环)取最近一条
        DnGovernanceIssue existing = issueMapper.selectOne(new QueryWrapper<DnGovernanceIssue>()
                .eq("issue_type", ISSUE_TYPE).eq("object_ref", objectRef)
                .orderByDesc("updated_at").last("LIMIT 1"));
        if (existing != null && !"CLOSED".equals(existing.getStatus())) {
            String st = existing.getStatus();
            String newDesc = desc;
            if ("RESOLVED".equals(st) || "VERIFIED".equals(st)) {
                // 再次越界: 已解决/已验证的单重开(状态机合法路径 →FIXING→OPEN), 不另建新单防单量膨胀
                reopen(existing.getId(), st);
                newDesc = trunc("[再次越界] " + desc, 500);
            }
            // 只更内容字段, 不整对象回写(防旧 status 覆盖 transition 结果)
            issueMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnGovernanceIssue>()
                    .eq("id", existing.getId())
                    .set("title", title).set("severity", sev).set("description", newDesc)
                    .set("updated_at", LocalDateTime.now()));
            return;
        }
        DnGovernanceIssue issue = new DnGovernanceIssue();
        issue.setIssueType(ISSUE_TYPE); issue.setDimension("指标预警"); issue.setObjectRef(objectRef);
        issue.setTitle(title); issue.setSeverity(sev); issue.setOwner(m.getOwner()); issue.setStatus("OPEN");
        issue.setDescription(desc);
        issue.setCreatedAt(LocalDateTime.now()); issue.setUpdatedAt(LocalDateTime.now());
        issueService.create(issue);
        log.info("指标预警自动建工单 metricId={} ruleId={} value={}", m.getId(), r.getId(), valStr);
        // IV-1 埋点③: 预警建单通知指标负责人
        notificationService.notify(m.getOwner(), "METRIC_ALERT", title, "govissue", issue.getId(), null);
    }

    /** 指标恢复: 仅自动关 OPEN 单(不动 FIXING/RESOLVED, 尊重人工流程; 对齐质量侧自动关单语义) */
    private void closeRecovered(DnMetric m, BigDecimal value) {
        try {
            DnGovernanceIssue open = issueMapper.selectOne(new QueryWrapper<DnGovernanceIssue>()
                    .eq("issue_type", ISSUE_TYPE).eq("object_ref", "metric:" + m.getId())
                    .eq("status", "OPEN").orderByDesc("updated_at").last("LIMIT 1"));
            if (open == null) return;
            issueService.transition(open.getId(), "CLOSED", "metric-alert");
            // 只补备注, 不整对象回写(旧对象 status=OPEN 会覆盖掉刚置的 CLOSED)
            issueMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnGovernanceIssue>()
                    .eq("id", open.getId())
                    .set("description", trunc("[自动关单] 指标已恢复, 当前值 " + value.toPlainString()
                            + (open.getDescription() == null ? "" : "\n" + open.getDescription()), 500)));
            log.info("指标恢复自动关单 metricId={} issueId={}", m.getId(), open.getId());
        } catch (Exception e) {
            log.warn("指标恢复自动关单失败 metricId={}: {}", m.getId(), e.getMessage());
        }
    }

    /** 已闭环单重开: 走状态机合法两跳 RESOLVED/VERIFIED→FIXING→OPEN */
    private void reopen(Long issueId, String fromStatus) {
        try {
            issueService.transition(issueId, "FIXING", "metric-alert");
            issueService.transition(issueId, "OPEN", "metric-alert");
        } catch (Exception e) {
            log.warn("指标预警重开工单失败 issueId={} from={}: {}", issueId, fromStatus, e.getMessage());
        }
    }

    static int severityRank(String s) {
        String u = normalizeSeverity(s);
        return "HIGH".equals(u) ? 3 : "MEDIUM".equals(u) ? 2 : 1;
    }

    /** 字符串非空白判定(私有小工具) */
    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ---- 纯函数(可单测) ----

    /** 阈值判定：value 是否触发预警(越界)。null 不触发。 */
    static boolean isBreach(String op, BigDecimal value, BigDecimal min, BigDecimal max) {
        if (op == null || value == null) return false;
        switch (op.toUpperCase()) {
            case "GT": return min != null && value.compareTo(min) > 0;
            case "LT": return min != null && value.compareTo(min) < 0;
            case "GE": return min != null && value.compareTo(min) >= 0;
            case "LE": return min != null && value.compareTo(min) <= 0;
            case "NE": return min != null && value.compareTo(min) != 0;
            case "OUT": return min != null && max != null && (value.compareTo(min) < 0 || value.compareTo(max) > 0);
            case "IN":  return min != null && max != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
            default: return false;
        }
    }

    static String breachDesc(String op, BigDecimal min, BigDecimal max) {
        if (op == null) return "";
        String mn = min == null ? "?" : min.toPlainString(), mx = max == null ? "?" : max.toPlainString();
        switch (op.toUpperCase()) {
            case "GT": return "> " + mn;
            case "LT": return "< " + mn;
            case "GE": return "≥ " + mn;
            case "LE": return "≤ " + mn;
            case "NE": return "≠ " + mn;
            case "OUT": return "超出区间 [" + mn + ", " + mx + "]";
            case "IN": return "落入区间 [" + mn + ", " + mx + "]";
            default: return op;
        }
    }

    static String normalizeSeverity(String s) {
        if (s == null) return "MEDIUM";
        String u = s.trim().toUpperCase();
        return ("HIGH".equals(u) || "MEDIUM".equals(u) || "LOW".equals(u)) ? u : "MEDIUM";
    }

    private static String trunc(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
