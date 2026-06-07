package com.datanote.controller;

import com.datanote.model.DnClassificationLevel;
import com.datanote.model.DnSensitiveRule;
import com.datanote.model.R;
import com.datanote.service.ClassificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分类分级 Controller — 分级模型 / 敏感规则 CRUD / 采样识别 / 人工打标
 */
@Slf4j
@RestController
@RequestMapping("/api/gov/classification")
@RequiredArgsConstructor
@Tag(name = "分类分级", description = "分级模型、敏感识别规则、采样识别与打标")
public class ClassificationController {

    private final ClassificationService classificationService;

    @Operation(summary = "查询分级模型(可按 scheme 过滤)")
    @GetMapping("/levels")
    public R<List<DnClassificationLevel>> levels(@RequestParam(required = false) String scheme) {
        return R.ok(classificationService.levels(scheme));
    }

    @Operation(summary = "敏感资产盘点(带含敏感字段标签的表+敏感列数)")
    @GetMapping("/sensitive-tables")
    public R<List<Map<String, Object>>> sensitiveTables() {
        return R.ok(classificationService.sensitiveTables());
    }

    @Operation(summary = "敏感规则列表")
    @GetMapping("/rules")
    public R<List<DnSensitiveRule>> rules() {
        return R.ok(classificationService.listRules());
    }

    @Operation(summary = "新增/更新敏感规则")
    @PostMapping("/rules")
    public R<DnSensitiveRule> saveRule(@RequestBody DnSensitiveRule rule) {
        if (rule.getRuleName() == null || rule.getRuleName().trim().isEmpty()) {
            return R.fail("规则名不能为空");
        }
        if (rule.getMatchType() == null || rule.getPattern() == null
                || rule.getSensitiveType() == null) {
            return R.fail("匹配方式/模式/敏感类型不能为空");
        }
        return R.ok(classificationService.saveRule(rule));
    }

    @Operation(summary = "删除敏感规则")
    @DeleteMapping("/rules/{id}")
    public R<String> deleteRule(@PathVariable Long id) {
        classificationService.deleteRule(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "启用/停用敏感规则")
    @PostMapping("/rules/{id}/toggle")
    public R<String> toggleRule(@PathVariable Long id) {
        classificationService.toggleRule(id);
        return R.ok("ok");
    }

    @Operation(summary = "敏感分布热力(按表统计敏感列数Top30)")
    @GetMapping("/heatmap")
    public R<List<Map<String, Object>>> heatmap() {
        return R.ok(classificationService.sensitiveHeatmap());
    }

    @Operation(summary = "打标审计溯源(按库.表)")
    @GetMapping("/audit-trail")
    public R<List<com.datanote.model.DnLabelAudit>> auditTrail(@RequestParam String db, @RequestParam String table) {
        return R.ok(classificationService.auditTrail(db, table));
    }

    @Operation(summary = "对表采样识别，返回敏感候选")
    @GetMapping("/scan")
    public R<List<Map<String, Object>>> scan(@RequestParam String db, @RequestParam String table) {
        try {
            return R.ok(classificationService.scanTable(db, table));
        } catch (Exception e) {
            log.error("采样识别失败: {}.{}", db, table, e);
            return R.fail("采样识别失败: " + e.getMessage());
        }
    }

    @Operation(summary = "人工确认打标(回写元数据+审计留痕)")
    @PostMapping("/confirm")
    public R<String> confirm(@RequestBody Map<String, String> body) {
        String db = body.get("db");
        String table = body.get("table");
        String column = body.get("column");
        if (db == null || db.isEmpty() || table == null || table.isEmpty()
                || column == null || column.isEmpty()) {
            return R.fail("库名/表名/列名不能为空");
        }
        classificationService.confirm(db, table, column, body.get("newLevel"),
                body.get("sensitiveType"), body.get("operator"), body.get("reason"));
        return R.ok("打标成功");
    }
}
