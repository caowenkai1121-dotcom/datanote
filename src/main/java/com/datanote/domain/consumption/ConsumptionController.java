package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.consumption.mapper.DnConsumptionLogMapper;
import com.datanote.domain.consumption.mapper.DnMetricAlertRuleMapper;
import com.datanote.domain.consumption.mapper.DnMetricValueMapper;
import com.datanote.domain.consumption.model.DnConsumptionLog;
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
    private final DnMetricValueMapper valueMapper;
    private final DnConsumptionLogMapper logMapper;
    private final DnMetricMapper metricMapper;
    private final DnMetricAlertRuleMapper alertRuleMapper;

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

    @Operation(summary = "指标最新值(开放取数 API: 供外部系统/看板按指标拉数, 含消费审计)")
    @GetMapping("/metric/{id}/value")
    public R<DnMetricValue> value(@PathVariable Long id, @RequestParam(required = false) String consumer) {
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
        List<DnMetricValue> rows = valueService.history(id, limit);
        // 落 targetCode: 趋势查看计入消费排行/热度(行有 code 直取, 空历史回查指标)
        String code = null;
        for (DnMetricValue v : rows) { if (v != null && v.getMetricCode() != null) { code = v.getMetricCode(); break; } }
        if (code == null) {
            DnMetric m = metricMapper.selectById(id);
            if (m != null) code = m.getMetricCode();
        }
        valueService.logConsumption(consumer, "METRIC_HISTORY", code, "QUERY", (long) rows.size(), null, true, "history " + rows.size());
        return R.ok(rows);
    }

    @Operation(summary = "按 code 批量取最新值(开放取数 API: 看板批量拉数, 上限200)")
    @PostMapping("/metric/values")
    public R<List<Map<String, Object>>> batchValues(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> codes = body == null ? null : (List<String>) body.get("codes");
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
        List<DnMetricValue> rows = valueService.history(id, limit);
        DnMetric m = metricMapper.selectById(id);
        String code = m == null ? ("metric" + id) : m.getMetricCode();
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
            body = sb.toString().getBytes(StandardCharsets.UTF_8); ct = "application/json; charset=UTF-8"; fn = code + ".json";
        } else {
            StringBuilder sb = new StringBuilder("﻿时间,指标值,状态,业务日期\n");
            for (DnMetricValue v : rows) {
                sb.append(csvCell(v.getCreatedAt())).append(',')
                  .append(csvCell(v.getMetricValue())).append(',')
                  .append(csvCell(v.getRunStatus())).append(',')
                  .append(csvCell(v.getBizDate())).append('\n');
            }
            body = sb.toString().getBytes(StandardCharsets.UTF_8); ct = "text/csv; charset=UTF-8"; fn = code + ".csv";
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
