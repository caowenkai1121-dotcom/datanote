package com.datanote.domain.governance;

import com.datanote.domain.governance.model.DnLifecyclePolicy;
import com.datanote.common.model.R;
import com.datanote.domain.governance.LifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 生命周期 Controller — 策略 CRUD / 应用下发 / 资产采集 / 无用表清单 / 标记销毁 / 成本排行
 */
@Slf4j
@RestController
@RequestMapping("/api/gov/lifecycle")
@RequiredArgsConstructor
@Tag(name = "生命周期", description = "生命周期策略、资产成本、无用表识别与销毁护栏")
public class LifecycleController {

    private final LifecycleService lifecycleService;

    // ===== 策略 CRUD =====

    @Operation(summary = "策略列表")
    @GetMapping("/policies")
    public R<List<DnLifecyclePolicy>> policies() {
        return R.ok(lifecycleService.listPolicies());
    }

    @Operation(summary = "新增/更新策略")
    @PostMapping("/policies")
    public R<DnLifecyclePolicy> savePolicy(@RequestBody DnLifecyclePolicy policy) {
        if (policy.getDbName() == null || policy.getDbName().trim().isEmpty()
                || policy.getTableName() == null || policy.getTableName().trim().isEmpty()) {
            return R.fail("库名/表名不能为空");
        }
        if (policy.getPolicyType() == null || policy.getPolicyType().trim().isEmpty()) {
            return R.fail("策略类型不能为空(HOT_COLD/TTL/ARCHIVE)");
        }
        return R.ok(lifecycleService.savePolicy(policy));
    }

    @Operation(summary = "删除策略")
    @DeleteMapping("/policies/{id}")
    public R<String> deletePolicy(@PathVariable Long id) {
        lifecycleService.deletePolicy(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "启用/停用策略")
    @PostMapping("/policies/{id}/toggle")
    public R<String> togglePolicy(@PathVariable Long id) {
        lifecycleService.togglePolicy(id);
        return R.ok("ok");
    }

    @Operation(summary = "应用策略(下发 Doris DDL，失败降级 PENDING)")
    @PostMapping("/policies/{id}/apply")
    public R<DnLifecyclePolicy> applyPolicy(@PathVariable Long id) {
        return R.ok(lifecycleService.applyPolicy(id));
    }

    // ===== 资产采集 / 成本 =====

    @Operation(summary = "采集资产快照(体量/行数/成本)")
    @PostMapping("/stats/collect")
    public R<String> collectStats() {
        int n = lifecycleService.collectStats();
        return R.ok("已采集 " + n + " 条资产快照");
    }

    @Operation(summary = "成本排行")
    @GetMapping("/cost")
    public R<List<Map<String, Object>>> cost(@RequestParam(required = false, defaultValue = "50") int limit) {
        return R.ok(lifecycleService.costRanking(limit));
    }

    // ===== 无用表识别 / 销毁护栏 =====

    @Operation(summary = "无用表候选清单(四要素打分)")
    @GetMapping("/unused")
    public R<List<Map<String, Object>>> unused() {
        return R.ok(lifecycleService.unusedTables());
    }

    @Operation(summary = "标记销毁(进入软删宽限期，三道护栏)")
    @PostMapping("/drop")
    public R<DnLifecyclePolicy> drop(@RequestBody Map<String, String> body) {
        return R.ok(lifecycleService.markForDrop(body.get("db"), body.get("table"),
                body.get("approver"), body.get("reason")));
    }

    @Operation(summary = "执行到期销毁(仅宽限期满且复核护栏后真正删表)")
    @PostMapping("/drop/execute")
    public R<List<Map<String, Object>>> executeDrops() {
        return R.ok(lifecycleService.executeDueDrops());
    }
}
