package com.datanote.domain.consumption;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.consumption.mapper.DnConsumptionLogMapper;
import com.datanote.domain.consumption.mapper.DnMetricValueMapper;
import com.datanote.domain.consumption.model.DnConsumptionLog;
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
        List<DnMetric> all = metricMapper.selectList(new QueryWrapper<DnMetric>().eq("status", 1));
        int ok = 0, fail = 0;
        for (DnMetric m : all) {
            try {
                DnMetricValue v = valueService.calc(m.getId(), operator == null ? "calc-all" : operator);
                if ("success".equals(v.getRunStatus())) ok++; else fail++;
            } catch (Exception e) { fail++; }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", all.size()); r.put("success", ok); r.put("failed", fail);
        return R.ok(r);
    }

    @Operation(summary = "指标最新值")
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
        valueService.logConsumption(consumer, "METRIC_HISTORY", null, "QUERY", (long) rows.size(), null, true, "history " + rows.size());
        return R.ok(rows);
    }

    @Operation(summary = "按 code 批量取最新值")
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

    @Operation(summary = "消费审计流水(近100, 可按 targetCode 过滤)")
    @GetMapping("/log/list")
    public R<List<DnConsumptionLog>> logList(@RequestParam(required = false) String targetCode) {
        QueryWrapper<DnConsumptionLog> qw = new QueryWrapper<>();
        if (targetCode != null && !targetCode.isEmpty()) qw.eq("target_code", targetCode);
        qw.orderByDesc("created_at").last("LIMIT 100");
        return R.ok(logMapper.selectList(qw));
    }

    @Operation(summary = "消费热度(按指标 Top, 近30天调用数)")
    @GetMapping("/log/heat")
    public R<List<Map<String, Object>>> logHeat() {
        QueryWrapper<DnConsumptionLog> qw = new QueryWrapper<>();
        qw.select("target_code", "COUNT(*) AS cnt")
          .isNotNull("target_code").ne("target_code", "")
          .groupBy("target_code").orderByDesc("cnt").last("LIMIT 20");
        return R.ok(logMapper.selectMaps(qw));
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
