package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.consumption.mapper.DnMetricAlertRuleMapper;
import com.datanote.domain.consumption.model.DnMetricAlertRule;
import com.datanote.domain.consumption.model.DnMetricValue;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.model.DnMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 数据消费层 Controller（填补断点④：治理成果无消费出口）。
 * 指标取值/历史/批量/新鲜度/僵尸/概览/消费审计/导出 —— 让指标成果可查询、可看板、可导出。
 * 取值复用指标执行引擎；导出含审计；只消费已发布(status=1)指标。
 */
@Slf4j
@RestController
@RequestMapping("/api/consumption")
@Tag(name = "数据消费层", description = "指标取值/历史/看板/导出/消费审计")
@RequiredArgsConstructor
public class ConsumptionController {

    private final MetricValueService valueService;
    private final DnMetricMapper metricMapper;
    private final DnMetricAlertRuleMapper alertRuleMapper;
    private final MetricDetailService metricDetailService;

    /** 取已发布(status=1)指标; 未发布/不存在返回 null(调用方按自身返回体处理拒绝)。 */
    private DnMetric publishedMetric(Long id) {
        DnMetric m = metricMapper.selectById(id);
        return (m == null || m.getStatus() == null || m.getStatus() != 1) ? null : m;
    }

    @Operation(summary = "计算指标当前值并落快照")
    @PostMapping("/metric/{id}/calc")
    public R<DnMetricValue> calc(@PathVariable Long id, @RequestParam(required = false) String operator) {
        try {
            return R.ok(valueService.calc(id, operator));
        } catch (BusinessException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "批量计算全部启用指标")
    @PostMapping("/metric/calc-all")
    public R<Map<String, Object>> calcAll(@RequestParam(required = false) String operator) {
        return R.ok(valueService.calcAllEnabled(operator == null ? "calc-all" : operator, null));
    }

    @Operation(summary = "指标详情驾驶舱聚合(当前值/目标/达成率/环比同比/预测/趋势/告警/质量/血缘/相关)")
    @GetMapping("/metric/{id}/detail")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        try {
            return R.ok(metricDetailService.detail(id));
        } catch (BusinessException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "指标最新值(开放取数 API: 供外部系统/看板按指标拉数, 含消费审计)")
    @GetMapping("/metric/{id}/value")
    public R<DnMetricValue> value(@PathVariable Long id, @RequestParam(required = false) String consumer) {
        if (publishedMetric(id) == null) {
            return R.fail("指标未发布(status≠1)，不可消费");
        }
        DnMetricValue v = valueService.latest(id);
        valueService.logConsumption(consumer, "METRIC_VALUE", v == null ? null : v.getMetricCode(), "QUERY",
                v == null ? 0L : 1L, null, v != null, v == null ? "无值" : "ok");
        return R.ok(v);
    }

    @Operation(summary = "指标值历史(时间序列)")
    @GetMapping("/metric/{id}/history")
    public R<List<DnMetricValue>> history(@PathVariable Long id,
                                          @RequestParam(defaultValue = "30") int limit,
                                          @RequestParam(required = false) String consumer) {
        DnMetric metric = publishedMetric(id);
        if (metric == null) {
            return R.fail("指标未发布(status≠1)，不可消费");
        }
        List<DnMetricValue> rows = valueService.history(id, limit);
        // 落 targetCode: 趋势查看计入消费排行/热度(行有 code 直取, 空历史回查指标)
        String code = null;
        for (DnMetricValue v : rows) { if (v != null && v.getMetricCode() != null) { code = v.getMetricCode(); break; } }
        if (code == null) code = metric.getMetricCode();
        valueService.logConsumption(consumer, "METRIC_HISTORY", code, "QUERY", (long) rows.size(), null, true, "history " + rows.size());
        return R.ok(rows);
    }

    @Operation(summary = "按 code 批量取最新值(开放取数 API: 看板批量拉数, 上限200)")
    @PostMapping("/metric/values")
    public R<List<Map<String, Object>>> batchValues(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("codes");
        if (raw != null && !(raw instanceof List)) {
            return R.fail("codes 必须为字符串数组");
        }
        // 逐元素 String.valueOf 转换, 兼容数字数组, 避免 (List<String>) 强转后 c.trim() 抛 ClassCastException
        List<String> codes = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null) codes.add(String.valueOf(o));
            }
        }
        return R.ok(valueService.batchLatestByCode(codes));
    }

    @Operation(summary = "指标新鲜度列表")
    @GetMapping("/metric/freshness")
    public R<List<Map<String, Object>>> freshness() {
        return R.ok(valueService.freshness());
    }

    @Operation(summary = "僵尸指标(启用但从未被消费取值)")
    @GetMapping("/metric/zombies")
    public R<List<DnMetric>> zombies() {
        return R.ok(valueService.zombies());
    }

    @Operation(summary = "消费层概览")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(valueService.overview());
    }

    @Operation(summary = "资产影响联动: 给定库.表反查消费它的指标(改表前评估影响面)")
    @GetMapping("/asset-impact")
    public R<List<Map<String, Object>>> assetImpact(@RequestParam String db, @RequestParam String table) {
        return R.ok(valueService.assetImpact(db, table));
    }

    @Operation(summary = "指标消费排行(真实消费口径 Top20, days 时间窗可选)")
    @GetMapping("/metric-ranking")
    public R<List<Map<String, Object>>> metricRanking(@RequestParam(required = false) Integer days) {
        return R.ok(valueService.metricRanking(days));
    }

    @Operation(summary = "指标输入质量: 来源表质量规则+最新通过率, 给指标可信度信号")
    @GetMapping("/metric/{id}/input-quality")
    public R<Map<String, Object>> inputQuality(@PathVariable Long id) {
        return R.ok(valueService.inputQuality(id));
    }

    // (原 /log/list 端点已清退: 前端无消费, 审计流水经全局审计中心查看)

    // (原 /log/heat 已并入 /metric-ranking?days=30: 单一真实消费口径, 不再两卡两口径)

    @Operation(summary = "指标预警规则列表")
    @GetMapping("/metric/{id}/alert-rules")
    public R<List<DnMetricAlertRule>> alertRules(@PathVariable Long id) {
        return R.ok(alertRuleMapper.selectList(new QueryWrapper<DnMetricAlertRule>().eq("metric_id", id).orderByDesc("updated_at")));
    }

    @Operation(summary = "保存指标预警规则")
    @PostMapping("/metric/alert-rule/save")
    public R<DnMetricAlertRule> saveAlertRule(@RequestBody DnMetricAlertRule rule) {
        if (rule.getMetricId() == null || rule.getOp() == null || rule.getOp().trim().isEmpty()) {
            return R.fail("metricId 和 op 不能为空");
        }
        // 阈值校验: 防止缺界/min>max 的规则静默永不触发(与 isBreach 判定口径对齐)
        String op = rule.getOp().trim().toUpperCase();
        if ("IN".equals(op) || "OUT".equals(op)) {
            if (rule.getThresholdMin() == null || rule.getThresholdMax() == null) {
                return R.fail(op + " 规则需同时设置下限(thresholdMin)与上限(thresholdMax)");
            }
            if (rule.getThresholdMin().compareTo(rule.getThresholdMax()) > 0) {
                return R.fail("下限不能大于上限");
            }
        } else if (rule.getThresholdMin() == null) {
            return R.fail(op + " 规则需设置阈值(thresholdMin)");
        }
        if (rule.getEnabled() == null) rule.setEnabled(1);
        if (rule.getSeverity() == null) rule.setSeverity("MEDIUM");
        DnMetric m = metricMapper.selectById(rule.getMetricId());
        if (m != null) rule.setMetricCode(m.getMetricCode());
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        rule.setUpdatedAt(now);
        if (rule.getId() == null) { rule.setCreatedAt(now); alertRuleMapper.insert(rule); }
        else alertRuleMapper.updateById(rule);
        return R.ok(rule);
    }

    @Operation(summary = "删除指标预警规则")
    @DeleteMapping("/metric/alert-rule/{ruleId}")
    public R<String> deleteAlertRule(@PathVariable Long ruleId) {
        alertRuleMapper.deleteById(ruleId);
        return R.ok("删除成功");
    }

    @Operation(summary = "导出指标值历史(CSV/JSON, 含审计)")
    @GetMapping("/metric/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(defaultValue = "365") int limit,
                                         @RequestParam(required = false) String consumer) {
        DnMetric m = publishedMetric(id);
        if (m == null) {
            return ResponseEntity.status(403)
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body("指标未发布(status≠1)，不可消费".getBytes(StandardCharsets.UTF_8));
        }
        List<DnMetricValue> rows = valueService.history(id, limit);
        String code = m.getMetricCode();
        // 文件名净化: 防特殊字符(引号/CR/LF/斜杠)破坏 Content-Disposition 头或注入
        String safeCode = (code == null || code.trim().isEmpty()) ? "metric" : code.replaceAll("[^A-Za-z0-9._-]", "_");
        byte[] body;
        String ct, fn;
        if ("json".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < rows.size(); i++) {
                DnMetricValue v = rows.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"createdAt\":\"").append(v.getCreatedAt()).append("\",\"value\":")
                  .append(v.getMetricValue() == null ? "null" : v.getMetricValue())
                  .append(",\"status\":\"").append(v.getRunStatus()).append("\"}");
            }
            sb.append(']');
            body = sb.toString().getBytes(StandardCharsets.UTF_8); ct = "application/json; charset=UTF-8"; fn = safeCode + ".json";
        } else {
            StringBuilder sb = new StringBuilder("﻿时间,指标值,状态,业务日期\n");
            for (DnMetricValue v : rows) {
                sb.append(csvCell(v.getCreatedAt())).append(',')
                  .append(csvCell(v.getMetricValue())).append(',')
                  .append(csvCell(v.getRunStatus())).append(',')
                  .append(csvCell(v.getBizDate())).append('\n');
            }
            body = sb.toString().getBytes(StandardCharsets.UTF_8); ct = "text/csv; charset=UTF-8"; fn = safeCode + ".csv";
        }
        valueService.logConsumption(consumer, "EXPORT", code, "EXPORT", (long) rows.size(), null, true, "导出 " + format);
        return ResponseEntity.ok()
                .header("Content-Type", ct)
                .header("Content-Disposition", "attachment; filename=\"" + fn + "\"")
                .body(body);
    }

    /** CSV 单元格防注入 + 转义 */
    static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
