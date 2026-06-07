package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.consumption.mapper.DnConsumptionLogMapper;
import com.datanote.domain.consumption.mapper.DnMetricValueMapper;
import com.datanote.domain.consumption.model.DnConsumptionLog;
import com.datanote.domain.consumption.model.DnMetricValue;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
import com.datanote.domain.integration.HiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 指标值引擎（消费层核心）—— 复用 {@link HiveService#executeSQL} 按 dn_metric.calc_formula 计算指标值并落库，
 * 供查询/看板/导出消费。只计算 status=1 且 calc_formula 非空的已发布指标（业务红线：未发布/僵尸指标不可消费）。
 * 不重复造执行轮子；所有计算/消费写 dn_consumption_log 审计流水。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricValueService {

    private final DnMetricMapper metricMapper;
    private final DnMetricValueMapper valueMapper;
    private final DnConsumptionLogMapper logMapper;
    private final HiveService hiveService;
    private final MetricAlertService metricAlertService;

    /** 指标值新鲜度阈值（小时）：超过视为陈旧 */
    static final long FRESH_HOURS = 26;

    /** 计算单个指标当前值并落快照（仅 status=1 + 有公式）。失败也落 error 快照，不抛断主流程。 */
    public DnMetricValue calc(Long metricId, String operator) {
        DnMetric m = metricMapper.selectById(metricId);
        if (m == null) throw new BusinessException("指标不存在: " + metricId);
        if (m.getStatus() == null || m.getStatus() != 1) throw new BusinessException("指标未发布(status≠1)，不可消费: " + m.getMetricCode());
        String sql = m.getCalcFormula();
        if (sql == null || sql.trim().isEmpty()) throw new BusinessException("指标无计算公式(calc_formula)，无法取值: " + m.getMetricCode());

        DnMetricValue v = new DnMetricValue();
        v.setMetricId(metricId); v.setMetricCode(m.getMetricCode()); v.setDims(m.getDimensions());
        v.setCalcSql(sql); v.setCreatedBy(operator); v.setCreatedAt(LocalDateTime.now());
        long start = System.currentTimeMillis();
        BigDecimal num = null;
        try {
            Map<String, Object> r = hiveService.executeSQL(sql);
            Object firstCell = firstCell(r);
            num = parseMetricValue(firstCell);
            if (num != null) v.setMetricValue(num);
            else v.setValueText(firstCell == null ? null : String.valueOf(firstCell));
            v.setRunStatus("success");
        } catch (Exception e) {
            v.setRunStatus("error");
            v.setErrorMsg(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
            log.warn("指标取值失败 metricId={}: {}", metricId, e.getMessage());
        }
        v.setDurationMs(System.currentTimeMillis() - start);
        valueMapper.insert(v);
        // 闭合 消费→治理：成功取得数值后判定预警规则，越界自动建治理工单(内部兜底不抛)
        if ("success".equals(v.getRunStatus()) && num != null) {
            metricAlertService.checkAndAlert(m, num);
        }
        logConsumption(operator, "CALC", m.getMetricCode(), "CALC", null, v.getDurationMs(),
                "success".equals(v.getRunStatus()), "success".equals(v.getRunStatus()) ? "计算成功" : v.getErrorMsg());
        return v;
    }

    /** 指标最新一次成功值 */
    public DnMetricValue latest(Long metricId) {
        QueryWrapper<DnMetricValue> qw = new QueryWrapper<>();
        qw.eq("metric_id", metricId).eq("run_status", "success").orderByDesc("created_at").last("LIMIT 1");
        return valueMapper.selectOne(qw);
    }

    /** 指标值历史（时间序列，正序） */
    public List<DnMetricValue> history(Long metricId, int limit) {
        int n = limit <= 0 ? 30 : Math.min(limit, 365);
        QueryWrapper<DnMetricValue> qw = new QueryWrapper<>();
        qw.eq("metric_id", metricId).orderByDesc("created_at").last("LIMIT " + n);
        List<DnMetricValue> rows = valueMapper.selectList(qw);
        Collections.reverse(rows);
        return rows;
    }

    /** 按 code 批量取最新值（供看板批量拉取） */
    public List<Map<String, Object>> batchLatestByCode(List<String> codes) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (codes == null) return out;
        for (String code : codes) {
            QueryWrapper<DnMetric> mq = new QueryWrapper<>();
            mq.eq("metric_code", code).last("LIMIT 1");
            DnMetric m = metricMapper.selectOne(mq);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricCode", code);
            if (m != null) {
                row.put("metricName", m.getMetricName()); row.put("unit", m.getUnit());
                DnMetricValue v = latest(m.getId());
                row.put("value", v == null ? null : v.getMetricValue());
                row.put("valueAt", v == null ? null : v.getCreatedAt());
            }
            out.add(row);
        }
        return out;
    }

    /** 指标新鲜度：每个启用指标最近一次取值时间 + 是否陈旧 */
    public List<Map<String, Object>> freshness() {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (DnMetric m : enabledMetrics()) {
            DnMetricValue v = latest(m.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricId", m.getId()); row.put("metricCode", m.getMetricCode()); row.put("metricName", m.getMetricName());
            row.put("lastValue", v == null ? null : v.getMetricValue());
            row.put("lastValueAt", v == null ? null : v.getCreatedAt());
            long age = v == null || v.getCreatedAt() == null ? -1 : ageHours(v.getCreatedAt(), now);
            row.put("ageHours", age < 0 ? null : age);
            row.put("stale", v == null || isStale(age));
            out.add(row);
        }
        return out;
    }

    /** 僵尸指标：启用但从未被取值消费过(无任何值记录)的指标 */
    public List<DnMetric> zombies() {
        List<DnMetric> out = new ArrayList<>();
        for (DnMetric m : enabledMetrics()) {
            Long cnt = valueMapper.selectCount(new QueryWrapper<DnMetricValue>().eq("metric_id", m.getId()));
            if (cnt == null || cnt == 0) out.add(m);
        }
        return out;
    }

    /** 消费层概览聚合 */
    public Map<String, Object> overview() {
        Map<String, Object> o = new LinkedHashMap<>();
        long enabled = metricMapper.selectCount(new QueryWrapper<DnMetric>().eq("status", 1));
        long totalValues = valueMapper.selectCount(null);
        int zombieN = zombies().size();
        List<Map<String, Object>> fresh = freshness();
        long staleN = fresh.stream().filter(r -> Boolean.TRUE.equals(r.get("stale"))).count();
        long consume7d = logMapper.selectCount(new QueryWrapper<DnConsumptionLog>().ge("created_at", LocalDateTime.now().minusDays(7)));
        o.put("enabledMetrics", enabled);
        o.put("totalValues", totalValues);
        o.put("zombieMetrics", zombieN);
        o.put("staleMetrics", staleN);
        o.put("consume7d", consume7d);
        return o;
    }

    public void logConsumption(String consumer, String targetType, String targetCode, String action,
                               Long rows, Long durationMs, boolean success, String detail) {
        try {
            DnConsumptionLog l = new DnConsumptionLog();
            l.setConsumer(consumer == null ? "default" : consumer);
            l.setTargetType(targetType); l.setTargetCode(targetCode); l.setAction(action);
            l.setRowCount(rows); l.setDurationMs(durationMs); l.setSuccess(success ? 1 : 0);
            l.setDetail(detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail));
            l.setCreatedAt(LocalDateTime.now());
            logMapper.insert(l);
        } catch (Exception e) {
            log.warn("消费审计写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    private List<DnMetric> enabledMetrics() {
        return metricMapper.selectList(new QueryWrapper<DnMetric>().eq("status", 1).orderByDesc("updated_at"));
    }

    // ---- 纯函数(可单测) ----

    @SuppressWarnings("unchecked")
    static Object firstCell(Map<String, Object> execResult) {
        if (execResult == null) return null;
        Object rows = execResult.get("rows");
        if (!(rows instanceof List)) return null;
        List<?> list = (List<?>) rows;
        if (list.isEmpty()) return null;
        Object first = list.get(0);
        if (first instanceof List) {
            List<?> cells = (List<?>) first;
            return cells.isEmpty() ? null : cells.get(0);
        }
        return first;
    }

    /** 解析指标值为数字；非数字返回 null（落 value_text 兜底） */
    static BigDecimal parseMetricValue(Object cell) {
        if (cell == null) return null;
        String s = String.valueOf(cell).trim();
        if (s.isEmpty() || "NULL".equalsIgnoreCase(s)) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    static long ageHours(LocalDateTime from, LocalDateTime now) {
        if (from == null) return 0L;
        long h = java.time.Duration.between(from, now).toHours();
        return h < 0 ? 0L : h;
    }

    /** 陈旧判定：无值(age<0) 或 超过新鲜度阈值 */
    static boolean isStale(long ageHours) {
        return ageHours < 0 || ageHours >= FRESH_HOURS;
    }
}
