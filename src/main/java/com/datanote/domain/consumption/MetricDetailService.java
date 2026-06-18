package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.consumption.mapper.DnMetricAlertRuleMapper;
import com.datanote.domain.consumption.model.DnMetricAlertRule;
import com.datanote.domain.consumption.model.DnMetricValue;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.mapper.DnMetricRefMapper;
import com.datanote.domain.governance.model.DnMetric;
import com.datanote.domain.governance.model.DnMetricRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 指标详情聚合服务 — 把"指标定义 + 当前值/目标/达成率/环比/同比/预测 + 趋势序列 + 告警诊断 + 输入质量 + 血缘 + 相关指标"
 * 一次性聚合返回, 供指标详情驾驶舱页(对标原型 MetricDetail)消费, 避免前端多次往返。
 * 复用消费层既有 MetricValueService(history/latest/inputQuality) 与 MetricAlertService 阈值判定, 不重复造轮子。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricDetailService {

    private final DnMetricMapper metricMapper;
    private final MetricValueService valueService;
    private final DnMetricAlertRuleMapper alertRuleMapper;
    private final DnMetricRefMapper metricRefMapper;
    private final com.datanote.domain.governance.mapper.DnGovernanceIssueMapper issueMapper;
    private final com.datanote.domain.consumption.mapper.DnConsumptionLogMapper logMapper;

    public Map<String, Object> detail(Long id) {
        valueService.requireMetricAccess(id);
        DnMetric m = metricMapper.selectById(id);
        if (m == null) throw new BusinessException("指标不存在: " + id);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", m);

        // 趋势序列(正序, 仅成功且有数值)
        List<DnMetricValue> hist = valueService.history(id, 60);
        List<DnMetricValue> ok = new ArrayList<>();
        for (DnMetricValue v : hist) {
            if ("success".equalsIgnoreCase(v.getRunStatus()) && v.getMetricValue() != null) ok.add(v);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (DnMetricValue v : ok) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", v.getBizDate() != null ? v.getBizDate().toString() : (v.getCreatedAt() != null ? v.getCreatedAt().toLocalDate().toString() : ""));
            p.put("value", v.getMetricValue());
            trend.add(p);
        }
        out.put("trend", trend);

        DnMetricValue latestV = valueService.latest(id);
        BigDecimal current = latestV != null ? latestV.getMetricValue() : (ok.isEmpty() ? null : ok.get(ok.size() - 1).getMetricValue());
        BigDecimal target = m.getTargetValue();
        out.put("current", current);
        out.put("currentText", latestV != null ? latestV.getValueText() : null);
        out.put("bizDate", latestV != null && latestV.getBizDate() != null ? latestV.getBizDate().toString() : null);
        out.put("updatedAt", latestV != null && latestV.getCreatedAt() != null ? latestV.getCreatedAt().toString() : null);
        out.put("target", target);

        // 达成率 = 当前/目标
        if (current != null && target != null && target.compareTo(BigDecimal.ZERO) != 0) {
            out.put("achievement", current.multiply(BigDecimal.valueOf(100)).divide(target, 1, RoundingMode.HALF_UP));
        } else {
            out.put("achievement", null);
        }

        // 环比 MoM: 当前 vs 上一期
        out.put("mom", pct(current, ok.size() >= 2 ? ok.get(ok.size() - 2).getMetricValue() : null));

        // 同比 YoY: 当前 vs 约一年前最接近的一期
        BigDecimal yoyBase = null;
        if (latestV != null && latestV.getBizDate() != null && !ok.isEmpty()) {
            LocalDate targetDate = latestV.getBizDate().minusYears(1);
            long best = Long.MAX_VALUE;
            for (DnMetricValue v : ok) {
                if (v.getBizDate() == null) continue;
                long d = Math.abs(v.getBizDate().toEpochDay() - targetDate.toEpochDay());
                if (d < best && d <= 45) { best = d; yoyBase = v.getMetricValue(); }   // 容忍 45 天内匹配同期
            }
        }
        out.put("yoy", pct(current, yoyBase));

        // 预测: 最近最多 7 期线性回归外推下一期
        out.put("forecast", forecastNext(ok));

        // 告警诊断: 规则 vs 当前值越界判定
        List<DnMetricAlertRule> rules = alertRuleMapper.selectList(
                new QueryWrapper<DnMetricAlertRule>().eq("metric_id", id).orderByDesc("updated_at"));
        List<Map<String, Object>> alerts = new ArrayList<>();
        boolean anyBreach = false;
        String topSeverity = null;
        for (DnMetricAlertRule r : rules == null ? Collections.<DnMetricAlertRule>emptyList() : rules) {
            boolean enabled = r.getEnabled() != null && r.getEnabled() == 1;
            boolean breach = enabled && current != null
                    && MetricAlertService.isBreach(r.getOp(), current, r.getThresholdMin(), r.getThresholdMax());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", r.getId());
            a.put("op", r.getOp());
            a.put("thresholdMin", r.getThresholdMin());
            a.put("thresholdMax", r.getThresholdMax());
            a.put("severity", r.getSeverity());
            a.put("enabled", enabled);
            a.put("breach", breach);
            a.put("desc", MetricAlertService.breachDesc(r.getOp(), r.getThresholdMin(), r.getThresholdMax()));
            a.put("remark", r.getRemark());
            alerts.add(a);
            if (breach) { anyBreach = true; topSeverity = higher(topSeverity, r.getSeverity()); }
        }
        Map<String, Object> alertSummary = new LinkedHashMap<>();
        alertSummary.put("breached", anyBreach);
        alertSummary.put("severity", topSeverity);
        alertSummary.put("ruleCount", alerts.size());
        alertSummary.put("rules", alerts);
        out.put("alert", alertSummary);

        // 输入数据质量信号
        try { out.put("quality", valueService.inputQuality(id)); }
        catch (Exception e) { out.put("quality", null); }

        // 血缘关联(来源/维度/结果表)
        try {
            out.put("refs", metricRefMapper.selectList(
                    new QueryWrapper<DnMetricRef>().eq("metric_id", id).orderByAsc("ref_type", "id")));
        } catch (Exception e) { out.put("refs", new ArrayList<>()); }

        // 相关指标(同分类, 最多 6 个, 排除自身)
        List<Map<String, Object>> related = new ArrayList<>();
        if (m.getCategory() != null && !m.getCategory().trim().isEmpty()) {
            List<DnMetric> sameCat = metricMapper.selectList(new QueryWrapper<DnMetric>()
                    .eq("category", m.getCategory()).ne("id", id).orderByDesc("updated_at").last("LIMIT 6"));
            for (DnMetric rm : sameCat == null ? Collections.<DnMetric>emptyList() : sameCat) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rm.getId()); r.put("metricName", rm.getMetricName());
                r.put("metricCode", rm.getMetricCode()); r.put("unit", rm.getUnit());
                related.add(r);
            }
        }
        out.put("related", related);

        // 预警历史: 该指标自动建的治理工单(issue_type=METRIC, object_ref=metric:{id})
        try {
            List<com.datanote.domain.governance.model.DnGovernanceIssue> issues = issueMapper.selectList(
                    new QueryWrapper<com.datanote.domain.governance.model.DnGovernanceIssue>()
                            .eq("issue_type", "METRIC").eq("object_ref", "metric:" + id)
                            .orderByDesc("updated_at").last("LIMIT 10"));
            List<Map<String, Object>> issueHist = new ArrayList<>();
            for (com.datanote.domain.governance.model.DnGovernanceIssue is : issues == null ? Collections.<com.datanote.domain.governance.model.DnGovernanceIssue>emptyList() : issues) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("id", is.getId()); h.put("title", is.getTitle());
                h.put("severity", is.getSeverity()); h.put("status", is.getStatus());
                h.put("createdAt", is.getCreatedAt() != null ? is.getCreatedAt().toString() : null);
                h.put("updatedAt", is.getUpdatedAt() != null ? is.getUpdatedAt().toString() : null);
                issueHist.add(h);
            }
            out.put("alertIssues", issueHist);
        } catch (Exception e) { out.put("alertIssues", new ArrayList<>()); }

        // 下游消费方: 近 90 天消费审计中按消费方聚合(target_code=指标编码)
        try {
            List<Map<String, Object>> consumers = new ArrayList<>();
            if (m.getMetricCode() != null && !m.getMetricCode().trim().isEmpty()) {
                List<com.datanote.domain.consumption.model.DnConsumptionLog> logs = logMapper.selectList(
                        new QueryWrapper<com.datanote.domain.consumption.model.DnConsumptionLog>()
                                .eq("target_code", m.getMetricCode())
                                .ge("created_at", java.time.LocalDateTime.now().minusDays(90))
                                .orderByDesc("created_at").last("LIMIT 500"));
                Map<String, Integer> byConsumer = new LinkedHashMap<>();
                for (com.datanote.domain.consumption.model.DnConsumptionLog lg : logs == null ? Collections.<com.datanote.domain.consumption.model.DnConsumptionLog>emptyList() : logs) {
                    String who = lg.getConsumer() == null || lg.getConsumer().trim().isEmpty() ? "(匿名)" : lg.getConsumer().trim();
                    byConsumer.merge(who, 1, Integer::sum);
                }
                byConsumer.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8).forEach(en -> {
                    Map<String, Object> cmap = new LinkedHashMap<>();
                    cmap.put("consumer", en.getKey()); cmap.put("count", en.getValue());
                    consumers.add(cmap);
                });
            }
            out.put("consumers", consumers);
        } catch (Exception e) { out.put("consumers", new ArrayList<>()); }

        // 溯源: 计算公式 + 最近一次计算所用 SQL/耗时(便于排查口径)
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("calcFormula", m.getCalcFormula());
        if (latestV != null) {
            trace.put("lastCalcSql", latestV.getCalcSql());
            trace.put("lastDurationMs", latestV.getDurationMs());
            trace.put("lastRunStatus", latestV.getRunStatus());
        }
        out.put("trace", trace);

        return out;
    }

    /** 变化率百分比 (cur-base)/base*100; base 为空/0 返回 null。 */
    private static BigDecimal pct(BigDecimal cur, BigDecimal base) {
        if (cur == null || base == null || base.compareTo(BigDecimal.ZERO) == 0) return null;
        return cur.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 1, RoundingMode.HALF_UP);
    }

    /** 最近最多 7 期最小二乘线性外推下一期值; 不足 2 点返回 null。 */
    private static BigDecimal forecastNext(List<DnMetricValue> ok) {
        int n = ok.size();
        if (n < 2) return null;
        int k = Math.min(7, n);
        List<DnMetricValue> seg = ok.subList(n - k, n);
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < k; i++) {
            double x = i, y = seg.get(i).getMetricValue().doubleValue();
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double denom = k * sxx - sx * sx;
        if (denom == 0) return BigDecimal.valueOf(seg.get(k - 1).getMetricValue().doubleValue());
        double slope = (k * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / k;
        double next = slope * k + intercept;
        return BigDecimal.valueOf(next).setScale(2, RoundingMode.HALF_UP);
    }

    /** 取更高严重度(HIGH>MEDIUM>LOW)。 */
    private static String higher(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }
    private static int rank(String s) {
        if (s == null) return 0;
        switch (s.toUpperCase()) { case "HIGH": return 3; case "MEDIUM": return 2; case "LOW": return 1; default: return 0; }
    }
}
