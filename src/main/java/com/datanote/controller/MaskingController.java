package com.datanote.controller;

import com.datanote.model.DnMaskingPolicy;
import com.datanote.model.DnRowPolicy;
import com.datanote.model.R;
import com.datanote.service.MaskingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 脱敏策略 / 行级权限管理控制器（M9）。
 */
@Tag(name = "动态脱敏与行列权限")
@RestController
@RequestMapping("/api/gov/masking")
@RequiredArgsConstructor
public class MaskingController {

    private final MaskingService maskingService;

    // ---------------- 脱敏策略 ----------------

    @Operation(summary = "脱敏策略列表")
    @GetMapping("/policies")
    public R<List<DnMaskingPolicy>> listPolicies() {
        return R.ok(maskingService.listMaskingPolicies());
    }

    @Operation(summary = "新增/更新脱敏策略")
    @PostMapping("/policies")
    public R<DnMaskingPolicy> savePolicy(@RequestBody DnMaskingPolicy policy) {
        if (policy.getPolicyName() == null || policy.getPolicyName().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "策略名不能为空");
        }
        if (policy.getMatchDim() == null || policy.getMatchDim().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "匹配维度不能为空");
        }
        return R.ok(maskingService.saveMaskingPolicy(policy));
    }

    @Operation(summary = "删除脱敏策略")
    @DeleteMapping("/policies/{id}")
    public R<String> deletePolicy(@PathVariable Long id) {
        maskingService.deleteMaskingPolicy(id);
        return R.ok("删除成功");
    }

    // ---------------- 行级权限 ----------------

    @Operation(summary = "行策略列表")
    @GetMapping("/row-policies")
    public R<List<DnRowPolicy>> listRowPolicies() {
        return R.ok(maskingService.listRowPolicies());
    }

    @Operation(summary = "新增/更新行策略")
    @PostMapping("/row-policies")
    public R<DnRowPolicy> saveRowPolicy(@RequestBody DnRowPolicy policy) {
        if (policy.getRoleCode() == null || policy.getRoleCode().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "角色编码不能为空");
        }
        if (policy.getDbName() == null || policy.getTableName() == null
                || policy.getRowFilter() == null || policy.getRowFilter().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "库名/表名/行过滤条件不能为空");
        }
        return R.ok(maskingService.saveRowPolicy(policy));
    }

    @Operation(summary = "删除行策略")
    @DeleteMapping("/row-policies/{id}")
    public R<String> deleteRowPolicy(@PathVariable Long id) {
        maskingService.deleteRowPolicy(id);
        return R.ok("删除成功");
    }
}
