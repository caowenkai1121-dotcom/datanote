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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final com.datanote.domain.governance.mapper.DnMetricRefMapper metricRefMapper;
    private final com.datanote.domain.governance.mapper.DnQualityRuleMapper qualityRuleMapper;
    private final com.datanote.domain.governance.mapper.DnQualityRunMapper qualityRunMapper;

    /** 指标值新鲜度阈值（小时）：超过视为陈旧 */
    static final long FRESH_HOURS = 26;

    /** 批量取值入参上限（看板单次最多拉取的指标 code 数），防超大请求拖垮库 */
    private static final int MAX_BATCH_CODES = 200;

    /**
     * 计算单个指标当前值并落快照（仅 status=1 + 有公式）。失败也落 error 快照，不抛断主流程。
     * 多次写（value 落库 + 预警可能建/刷工单）置于同一事务保证原子。
     */
    @Transactional
    public DnMetricValue calc(Long metricId, String operator) {
        return calc(metricId, operator, null);
    }

    /**
     * @param bizDate 业务日期: null=当日(手动计算); 调度方显式传昨日(T+1 口径由调用方决定, 平台不猜)
     */
    @Transactional
    public DnMetricValue calc(Long metricId, String operator, java.time.LocalDate bizDate) {
        if (metricId == null) throw new BusinessException("指标ID(metricId)不能为空");
        DnMetric m = metricMapper.selectById(metricId);
        if (m == null) throw new BusinessException("指标不存在: " + metricId);
        if (m.getStatus() == null || m.getStatus() != 1) throw new BusinessException("指标未发布(status≠1)，不可消费: " + m.getMetricCode());
        String sql = m.getCalcFormula();
        if (sql == null || sql.trim().isEmpty()) throw new BusinessException("指标无计算公式(calc_formula)，无法取值: " + m.getMetricCode());

        DnMetricValue v = new DnMetricValue();
        v.setMetricId(metricId); v.setMetricCode(m.getMetricCode()); v.setDims(m.getDimensions());
        v.setCalcSql(sql); v.setCreatedBy(operator); v.setCreatedAt(LocalDateTime.now());
        v.setBizDate(bizDate == null ? java.time.LocalDate.now() : bizDate);
        long start = System.currentTimeMillis();
        BigDecimal num = null;
        // 安全守卫: 公式只放行只读查询(SELECT/WITH 单语句), 防存储型任意 SQL 被调度每日执行
        String guard = readOnlyViolation(sql);
        if (guard != null) {
            v.setRunStatus("error");
            v.setErrorMsg(guard);
            log.warn("指标公式被只读守卫拦截 metricId={} code={}: {}", metricId, m.getMetricCode(), guard);
        } else {
            try {
                Map<String, Object> r = hiveService.executeSQL(sql, true);   // 纵深防御: 只读连接
                Object firstCell = firstCell(r);
                num = parseMetricValue(firstCell);
                if (num != null) v.setMetricValue(num);
                else v.setValueText(firstCell == null ? null : String.valueOf(firstCell));
                v.setRunStatus("success");
            } catch (Exception e) {
                v.setRunStatus("error");
                v.setErrorMsg(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
                log.warn("指标取值失败 metricId={} code={}: {}", metricId, m.getMetricCode(), e.getMessage());
            }
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

    /**
     * 一键/调度 计算全部启用指标(收敛 ConsumptionController 与 ScheduleService 的重复循环)。
     * operator='schedule' 时按 metric+bizDate 幂等: 同日已有 success 快照则跳过(防同日重复触发刷快照);
     * 手动来源保留追加语义(快照流水即审计)。
     */
    public Map<String, Object> calcAllEnabled(String operator, java.time.LocalDate bizDate) {
        List<DnMetric> metrics = enabledMetrics();
        int ok = 0, fail = 0, skip = 0;
        boolean idempotent = "schedule".equals(operator);
        java.time.LocalDate bd = bizDate == null ? java.time.LocalDate.now() : bizDate;
        for (DnMetric m : metrics) {
            try {
                if (idempotent && hasSuccessSnapshot(m.getId(), bd)) { skip++; continue; }
                DnMetricValue v = calc(m.getId(), operator, bizDate);
                if ("success".equals(v.getRunStatus())) ok++; else fail++;
            } catch (Exception e) {
                fail++;
                log.warn("指标批量计算异常 metricId={}: {}", m.getId(), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", metrics.size());
        out.put("success", ok);
        out.put("failed", fail);
        if (skip > 0) out.put("skipped", skip);
        return out;
    }

    private boolean hasSuccessSnapshot(Long metricId, java.time.LocalDate bizDate) {
        Long n = valueMapper.selectCount(new QueryWrapper<DnMetricValue>()
                .eq("metric_id", metricId).eq("biz_date", bizDate).eq("run_status", "success"));
        return n != null && n > 0;
    }

    /** 指标最新一次成功值 */
    public DnMetricValue latest(Long metricId) {
        if (metricId == null) throw new BusinessException("指标ID(metricId)不能为空");
        QueryWrapper<DnMetricValue> qw = new QueryWrapper<>();
        qw.eq("metric_id", metricId).eq("run_status", "success").orderByDesc("created_at").last("LIMIT 1");
        return valueMapper.selectOne(qw);
    }

    /** 指标值历史（时间序列，正序） */
    public List<DnMetricValue> history(Long metricId, int limit) {
        if (metricId == null) throw new BusinessException("指标ID(metricId)不能为空");
        int n = limit <= 0 ? 30 : Math.min(limit, 365);
        QueryWrapper<DnMetricValue> qw = new QueryWrapper<>();
        qw.eq("metric_id", metricId).orderByDesc("created_at").last("LIMIT " + n);
        List<DnMetricValue> rows = valueMapper.selectList(qw);
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        Collections.reverse(rows);
        return rows;
    }

    /** 按 code 批量取最新值（供看板批量拉取）。批量查指标+批量查最新值，消除逐 code N+1。 */
    public List<Map<String, Object>> batchLatestByCode(List<String> codes) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (codes == null || codes.isEmpty()) return out;
        if (codes.size() > MAX_BATCH_CODES) throw new BusinessException("单次批量取值最多 " + MAX_BATCH_CODES + " 个指标, 实际 " + codes.size());
        // 去空白并保序去重，仅用有效 code 一次性查指标
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        for (String c : codes) {
            if (c != null && !c.trim().isEmpty()) valid.add(c.trim());
        }
        Map<String, DnMetric> metricByCode = new HashMap<>();
        if (!valid.isEmpty()) {
            List<DnMetric> metrics = metricMapper.selectList(
                    new QueryWrapper<DnMetric>().in("metric_code", new ArrayList<>(valid)));
            if (metrics != null) {
                for (DnMetric m : metrics) {
                    if (m.getMetricCode() != null) metricByCode.putIfAbsent(m.getMetricCode(), m);
                }
            }
        }
        // 一次取出这批指标的全部最新成功值, 内存映射 metricId -> latest
        Map<Long, DnMetricValue> latestByMetricId = latestSuccessMap(
                metricByCode.values().stream().map(DnMetric::getId).filter(Objects::nonNull).collect(Collectors.toList()));
        for (String code : codes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricCode", code);
            DnMetric m = code == null ? null : metricByCode.get(code.trim());
            if (m != null) {
                row.put("metricName", m.getMetricName()); row.put("unit", m.getUnit());
                DnMetricValue v = latestByMetricId.get(m.getId());
                row.put("value", v == null ? null : v.getMetricValue());
                row.put("valueAt", v == null ? null : v.getCreatedAt());
            }
            out.add(row);
        }
        return out;
    }

    /** 指标新鲜度：每个启用指标最近一次取值时间 + 是否陈旧。批量取最新值消除逐指标 N+1。 */
    public List<Map<String, Object>> freshness() {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        List<DnMetric> metrics = enabledMetrics();
        Map<Long, DnMetricValue> latestByMetricId = latestSuccessMap(
                metrics.stream().map(DnMetric::getId).filter(Objects::nonNull).collect(Collectors.toList()));
        for (DnMetric m : metrics) {
            DnMetricValue v = latestByMetricId.get(m.getId());
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

    /** 僵尸指标：启用但从未被取值消费过(无任何值记录)的指标。一次查出有值的 metricId 集合, 消除逐指标计数。 */
    public List<DnMetric> zombies() {
        List<DnMetric> metrics = enabledMetrics();
        if (metrics.isEmpty()) return new ArrayList<>();
        Set<Long> hasValue = metricIdsWithAnyValue(
                metrics.stream().map(DnMetric::getId).filter(Objects::nonNull).collect(Collectors.toList()));
        List<DnMetric> out = new ArrayList<>();
        for (DnMetric m : metrics) {
            if (m.getId() == null || !hasValue.contains(m.getId())) out.add(m);
        }
        return out;
    }

    /** 消费层概览聚合 */
    public Map<String, Object> overview() {
        Map<String, Object> o = new LinkedHashMap<>();
        Long enabled = metricMapper.selectCount(new QueryWrapper<DnMetric>().eq("status", 1));
        Long totalValues = valueMapper.selectCount(null);
        int zombieN = zombies().size();
        List<Map<String, Object>> fresh = freshness();
        long staleN = fresh.stream().filter(r -> Boolean.TRUE.equals(r.get("stale"))).count();
        Long consume7d = logMapper.selectCount(new QueryWrapper<DnConsumptionLog>().ge("created_at", LocalDateTime.now().minusDays(7)));
        o.put("enabledMetrics", enabled == null ? 0L : enabled);
        o.put("totalValues", totalValues == null ? 0L : totalValues);
        o.put("zombieMetrics", zombieN);
        o.put("staleMetrics", staleN);
        o.put("consume7d", consume7d == null ? 0L : consume7d);
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
            log.warn("消费审计写入失败(不影响主流程) consumer={} action={} target={}: {}",
                    consumer, action, targetCode, e.getMessage());
        }
    }

    /** 资产影响联动：给定库.表，反查哪些指标消费它(经 DnMetricRef)+ 最新值。改表前评估影响面。 */
    public List<Map<String, Object>> assetImpact(String db, String table) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (db == null || db.trim().isEmpty() || table == null || table.trim().isEmpty()) return out;
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.datanote.domain.governance.model.DnMetricRef> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.eq("db_name", db).eq("table_name", table);
        List<com.datanote.domain.governance.model.DnMetricRef> refs = metricRefMapper.selectList(qw);
        if (refs == null || refs.isEmpty()) return out;
        // 按出现顺序收集去重 metricId, 一次批量查指标与最新值, 消除逐 ref 的 selectById/latest N+1
        LinkedHashMap<Long, String> refTypeById = new LinkedHashMap<>();
        for (com.datanote.domain.governance.model.DnMetricRef ref : refs) {
            if (ref.getMetricId() != null) refTypeById.putIfAbsent(ref.getMetricId(), ref.getRefType());
        }
        if (refTypeById.isEmpty()) return out;
        Map<Long, DnMetric> metricById = metricMapByIds(new ArrayList<>(refTypeById.keySet()));
        Map<Long, DnMetricValue> latestByMetricId = latestSuccessMap(new ArrayList<>(refTypeById.keySet()));
        for (Map.Entry<Long, String> e : refTypeById.entrySet()) {
            DnMetric m = metricById.get(e.getKey());
            if (m == null) continue;
            DnMetricValue v = latestByMetricId.get(m.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricId", m.getId()); row.put("metricCode", m.getMetricCode());
            row.put("metricName", m.getMetricName()); row.put("refType", e.getValue());
            row.put("lastValue", v == null ? null : v.getMetricValue());
            row.put("lastValueAt", v == null ? null : v.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** 指标输入质量联动：指标来源表(DnMetricRef)上的质量规则 + 各规则最新通过率，给指标可信度信号。 */
    public Map<String, Object> inputQuality(Long metricId) {
        if (metricId == null) throw new BusinessException("指标ID(metricId)不能为空");
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        int total = 0, fail = 0, noResult = 0;
        // 来源表去重
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.datanote.domain.governance.model.DnMetricRef> rq =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        rq.eq("metric_id", metricId);
        Set<String> seen = new LinkedHashSet<>();
        List<com.datanote.domain.governance.model.DnMetricRef> refs = metricRefMapper.selectList(rq);
        if (refs != null) {
            for (com.datanote.domain.governance.model.DnMetricRef ref : refs) {
                if (ref.getDbName() == null || ref.getTableName() == null) continue;
                seen.add(ref.getDbName() + "::" + ref.getTableName());
            }
        }
        for (String key : seen) {
            String[] dt = key.split("::", 2);
            if (dt.length < 2) continue;
            QueryWrapper<com.datanote.domain.governance.model.DnQualityRule> qq = new QueryWrapper<>();
            qq.eq("database_name", dt[0]).eq("table_name", dt[1]).eq("status", 1);
            List<com.datanote.domain.governance.model.DnQualityRule> rules = qualityRuleMapper.selectList(qq);
            if (rules == null) rules = new ArrayList<>();
            // 一次取这批规则的最新运行记录, 内存按 ruleId 取首条(最新), 消除逐规则 selectOne N+1
            Map<Long, com.datanote.domain.governance.model.DnQualityRun> latestRunByRuleId = latestRunMap(
                    rules.stream().map(com.datanote.domain.governance.model.DnQualityRule::getId)
                            .filter(Objects::nonNull).collect(Collectors.toList()));
            List<Map<String, Object>> ruleRows = new ArrayList<>();
            for (com.datanote.domain.governance.model.DnQualityRule rule : rules) {
                total++;
                com.datanote.domain.governance.model.DnQualityRun run = latestRunByRuleId.get(rule.getId());
                Map<String, Object> rr = new LinkedHashMap<>();
                rr.put("ruleName", rule.getRuleName()); rr.put("ruleType", rule.getRuleType());
                rr.put("severity", rule.getSeverity()); rr.put("dimension", rule.getDimension());
                if (run == null) { rr.put("passRate", null); rr.put("runStatus", "no_result"); rr.put("lastRunAt", null); noResult++; }
                else {
                    rr.put("passRate", run.getPassRate()); rr.put("runStatus", run.getRunStatus());
                    rr.put("lastRunAt", run.getFinishedAt());
                    if (run.getFailCount() != null && run.getFailCount() > 0) fail++;
                }
                ruleRows.add(rr);
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("db", dt[0]); t.put("table", dt[1]); t.put("ruleCount", rules.size()); t.put("rules", ruleRows);
            tables.add(t);
        }
        // 整体可信度信号
        String signal;
        if (total == 0) signal = "NO_RULES";
        else if (fail > 0) signal = "AT_RISK";
        else if (noResult == total) signal = "NO_RESULT";
        else signal = "HEALTHY";
        out.put("metricId", metricId); out.put("signal", signal);
        out.put("ruleTotal", total); out.put("ruleFail", fail); out.put("tables", tables);
        return out;
    }

    /**
     * 指标消费排行：按消费日志计数，Top 指标(含名称)。口径=真实消费白名单, CALC(计算)不计——防调度刷平排行。
     * @param days 时间窗(天); null=全部历史。原 /log/heat 近30天能力并入本口径(单卡双窗口)。
     */
    public List<Map<String, Object>> metricRanking(Integer days) {
        QueryWrapper<DnConsumptionLog> qw = new QueryWrapper<>();
        qw.select("target_code", "COUNT(*) AS cnt")
          .in("target_type", java.util.Arrays.asList("METRIC_VALUE", "METRIC_HISTORY", "EXPORT"))
          .isNotNull("target_code").ne("target_code", "");
        if (days != null && days > 0) qw.ge("created_at", LocalDateTime.now().minusDays(days));
        qw.groupBy("target_code").orderByDesc("cnt").last("LIMIT 20");
        List<Map<String, Object>> rows = logMapper.selectMaps(qw);
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        // 收集本批 code, 一次查名称, 内存映射 code -> metricName
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object code = r.get("target_code");
            if (code != null) codes.add(String.valueOf(code));
        }
        Map<String, String> nameByCode = new HashMap<>();
        if (!codes.isEmpty()) {
            List<DnMetric> metrics = metricMapper.selectList(
                    new QueryWrapper<DnMetric>().in("metric_code", new ArrayList<>(codes)));
            if (metrics != null) {
                for (DnMetric m : metrics) {
                    if (m.getMetricCode() != null) nameByCode.putIfAbsent(m.getMetricCode(), m.getMetricName());
                }
            }
        }
        for (Map<String, Object> r : rows) {
            Object code = r.get("target_code");
            r.put("metricName", code == null ? null : nameByCode.get(String.valueOf(code)));
        }
        return rows;
    }

    private List<DnMetric> enabledMetrics() {
        List<DnMetric> rows = metricMapper.selectList(new QueryWrapper<DnMetric>().eq("status", 1).orderByDesc("updated_at"));
        return rows == null ? new ArrayList<>() : rows;
    }

    // ---- 内部批量查询辅助(消除 N+1) ----

    /** 批量取一组指标各自的"最新成功值"，内存归约为 metricId -> 最新 DnMetricValue。空入参返回空表。 */
    private Map<Long, DnMetricValue> latestSuccessMap(List<Long> metricIds) {
        Map<Long, DnMetricValue> map = new HashMap<>();
        if (metricIds == null || metricIds.isEmpty()) return map;
        List<DnMetricValue> rows = valueMapper.selectList(new QueryWrapper<DnMetricValue>()
                .in("metric_id", metricIds).eq("run_status", "success").orderByDesc("created_at"));
        if (rows == null) return map;
        // 已按 created_at 倒序，首条即为最新；putIfAbsent 保留每个 metricId 的最新一条
        for (DnMetricValue v : rows) {
            if (v.getMetricId() != null) map.putIfAbsent(v.getMetricId(), v);
        }
        return map;
    }

    /** 一次查出给定指标中"存在任意值记录"的 metricId 集合（用于僵尸判定，避免逐指标 count）。 */
    private Set<Long> metricIdsWithAnyValue(List<Long> metricIds) {
        Set<Long> set = new HashSet<>();
        if (metricIds == null || metricIds.isEmpty()) return set;
        List<DnMetricValue> rows = valueMapper.selectList(new QueryWrapper<DnMetricValue>()
                .select("DISTINCT metric_id").in("metric_id", metricIds));
        if (rows == null) return set;
        for (DnMetricValue v : rows) {
            if (v.getMetricId() != null) set.add(v.getMetricId());
        }
        return set;
    }

    /** 批量按 id 查指标，内存映射 id -> DnMetric。空入参返回空表。 */
    private Map<Long, DnMetric> metricMapByIds(List<Long> ids) {
        Map<Long, DnMetric> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        List<DnMetric> metrics = metricMapper.selectBatchIds(ids);
        if (metrics == null) return map;
        for (DnMetric m : metrics) {
            if (m.getId() != null) map.put(m.getId(), m);
        }
        return map;
    }

    /** 批量取一组质量规则各自最新一次运行记录，内存归约为 ruleId -> 最新 DnQualityRun。 */
    private Map<Long, com.datanote.domain.governance.model.DnQualityRun> latestRunMap(List<Long> ruleIds) {
        Map<Long, com.datanote.domain.governance.model.DnQualityRun> map = new HashMap<>();
        if (ruleIds == null || ruleIds.isEmpty()) return map;
        List<com.datanote.domain.governance.model.DnQualityRun> rows = qualityRunMapper.selectList(
                new QueryWrapper<com.datanote.domain.governance.model.DnQualityRun>()
                        .in("rule_id", ruleIds).orderByDesc("started_at"));
        if (rows == null) return map;
        // 已按 started_at 倒序，每个 ruleId 首条即最新
        for (com.datanote.domain.governance.model.DnQualityRun run : rows) {
            if (run.getRuleId() != null) map.putIfAbsent(run.getRuleId(), run);
        }
        return map;
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

    /**
     * 只读守卫(纯函数): 剥离行/块注释后, 首词须 SELECT/WITH 且去尾分号后不得再含分号(拒多语句)。
     * 违规返回拦截原因; 合规返回 null。
     */
    static String readOnlyViolation(String sql) {
        if (sql == null) return "仅允许只读查询(SELECT/WITH)";
        String s = sql.replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            int i = line.indexOf("--");
            sb.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        String body = sb.toString().trim();
        if (body.endsWith(";")) body = body.substring(0, body.length() - 1).trim();
        if (body.isEmpty()) return "仅允许只读查询(SELECT/WITH)";
        if (body.indexOf(';') >= 0) return "仅允许只读查询: 不允许多语句(检测到分号)";
        String first = body.split("\\s+", 2)[0].toUpperCase();
        if (!"SELECT".equals(first) && !"WITH".equals(first)) {
            return "仅允许只读查询(SELECT/WITH), 检测到: " + first;
        }
        return null;
    }

    /** 解析指标值为数字；非数字返回 null（落 value_text 兜底） */
    static BigDecimal parseMetricValue(Object cell) {
        if (cell == null) return null;
        String s = String.valueOf(cell).trim();
        if (s.isEmpty() || "NULL".equalsIgnoreCase(s)) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    static long ageHours(LocalDateTime from, LocalDateTime now) {
        if (from == null || now == null) return 0L;
        long h = java.time.Duration.between(from, now).toHours();
        return h < 0 ? 0L : h;
    }

    /** 陈旧判定：无值(age<0) 或 超过新鲜度阈值 */
    static boolean isStale(long ageHours) {
        return ageHours < 0 || ageHours >= FRESH_HOURS;
    }
}
