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

    static final String ISSUE_TYPE = "METRIC";

    /** 指标计算出数值后调用：逐条启用规则判定，越界自动建/刷工单。 */
    public void checkAndAlert(DnMetric metric, BigDecimal value) {
        try {
            if (metric == null || value == null) return;
            List<DnMetricAlertRule> rules = ruleMapper.selectList(
                    new QueryWrapper<DnMetricAlertRule>().eq("metric_id", metric.getId()).eq("enabled", 1));
            for (DnMetricAlertRule r : rules) {
                if (isBreach(r.getOp(), value, r.getThresholdMin(), r.getThresholdMax())) {
                    raiseIssue(metric, r, value);
                }
            }
        } catch (Exception e) {
            log.warn("指标预警判定失败(不影响计算) metricId={}: {}", metric == null ? null : metric.getId(), e.getMessage());
        }
    }

    private void raiseIssue(DnMetric m, DnMetricAlertRule r, BigDecimal value) {
        String objectRef = "metric:" + r.getId();
        String title = trunc("[指标预警] " + (m.getMetricName() == null ? m.getMetricCode() : m.getMetricName())
                + " 当前值 " + value.toPlainString() + " " + breachDesc(r.getOp(), r.getThresholdMin(), r.getThresholdMax()), 200);
        String sev = normalizeSeverity(r.getSeverity());
        // 去重:同规则未关闭工单存在则刷新
        DnGovernanceIssue existing = issueMapper.selectOne(new QueryWrapper<DnGovernanceIssue>()
                .eq("issue_type", ISSUE_TYPE).eq("object_ref", objectRef).ne("status", "CLOSED")
                .orderByDesc("updated_at").last("LIMIT 1"));
        if (existing != null) {
            existing.setTitle(title); existing.setSeverity(sev); existing.setUpdatedAt(LocalDateTime.now());
            existing.setDescription("指标 " + m.getMetricCode() + " 触发预警规则 #" + r.getId() + "，当前值 " + value.toPlainString());
            issueMapper.updateById(existing);
            return;
        }
        DnGovernanceIssue issue = new DnGovernanceIssue();
        issue.setIssueType(ISSUE_TYPE); issue.setDimension("指标预警"); issue.setObjectRef(objectRef);
        issue.setTitle(title); issue.setSeverity(sev); issue.setOwner(m.getOwner()); issue.setStatus("OPEN");
        issue.setDescription("指标 " + m.getMetricCode() + " 触发预警规则 #" + r.getId() + "，当前值 " + value.toPlainString());
        issue.setCreatedAt(LocalDateTime.now()); issue.setUpdatedAt(LocalDateTime.now());
        issueService.create(issue);
        log.info("指标预警自动建工单 metricId={} ruleId={} value={}", m.getId(), r.getId(), value);
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
