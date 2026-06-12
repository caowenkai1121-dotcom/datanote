package com.datanote.platform.portal;

import com.datanote.domain.governance.model.DnGovernanceIssue;
import com.datanote.domain.governance.model.DnGovernanceMetric;
import com.datanote.common.model.R;
import com.datanote.domain.governance.HealthScoreService;
import com.datanote.domain.governance.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 治理健康分 Controller —— 健康分(当前/趋势/明细) + 工单(CRUD/流转/路由/排行)。
 * 批4#33: DCMM 成熟度自评(前端零调用孤岛)随汇入移除一并下架。
 */
@Slf4j
@RestController
@RequestMapping("/api/gov/health")
@RequiredArgsConstructor
@Tag(name = "治理健康分", description = "五维健康分、治理工单闭环")
public class HealthController {

    private final HealthScoreService healthScoreService;
    private final IssueService issueService;

    // ========== 健康分 ==========

    @Operation(summary = "当前健康分(最近快照,无则即时算)")
    @GetMapping("/score")
    public R<Map<String, Object>> score() {
        return R.ok(healthScoreService.current());
    }

    @Operation(summary = "重算健康分并写快照")
    @PostMapping("/score/refresh")
    public R<Map<String, Object>> refresh() {
        return R.ok(healthScoreService.computeAndSnapshot());
    }

    @Operation(summary = "健康分趋势(近 days 天快照)")
    @GetMapping("/score/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "30") int days) {
        return R.ok(healthScoreService.trend(days));
    }

    @Operation(summary = "五维明细(各维度分+取数来源+权重)")
    @GetMapping("/dimensions")
    public R<Map<String, Object>> dimensions() {
        return R.ok(healthScoreService.compute());
    }

    // ========== 工单 ==========

    @Operation(summary = "工单列表(可按 status/owner/dimension 过滤)")
    @GetMapping("/issues")
    public R<List<DnGovernanceIssue>> issues(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String owner,
                                             @RequestParam(required = false) String dimension) {
        return R.ok(issueService.list(status, owner, dimension));
    }

    @Operation(summary = "新建工单")
    @PostMapping("/issues")
    public R<DnGovernanceIssue> createIssue(@RequestBody DnGovernanceIssue issue) {
        if (issue.getTitle() == null || issue.getTitle().trim().isEmpty()) {
            return R.fail("标题不能为空");
        }
        if (issue.getIssueType() == null || issue.getIssueType().trim().isEmpty()) {
            return R.fail("问题类型不能为空");
        }
        return R.ok(issueService.create(issue));
    }

    @Operation(summary = "工单状态流转")
    @PostMapping("/issues/{id}/transition")
    public R<DnGovernanceIssue> transition(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.trim().isEmpty()) return R.fail("目标状态不能为空");
        try {
            return R.ok(issueService.transition(id, status, body.get("operator")));
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "指派负责人(按 owner 路由)")
    @PostMapping("/issues/{id}/assign")
    public R<DnGovernanceIssue> assign(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String owner = body == null ? null : body.get("owner");
            if (owner == null || owner.trim().isEmpty()) return R.fail("负责人不能为空");
            return R.ok(issueService.assign(id, owner.trim()));
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除工单")
    @DeleteMapping("/issues/{id}")
    public R<String> deleteIssue(@PathVariable Long id) {
        issueService.delete(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "工单排行榜(按 owner)")
    @GetMapping("/issues/leaderboard")
    public R<List<Map<String, Object>>> leaderboard() {
        return R.ok(issueService.leaderboard());
    }

    @Operation(summary = "工单运营统计(多维分布+未关/超期+解决率+平均处理时长)")
    @GetMapping("/issues/stats")
    public R<Map<String, Object>> issueStats() {
        return R.ok(issueService.stats());
    }

    @Operation(summary = "工单趋势(近 days 天每日新建/关闭)")
    @GetMapping("/issues/trend")
    public R<List<Map<String, Object>>> issueTrend(@RequestParam(defaultValue = "30") int days) {
        return R.ok(issueService.trend(days));
    }

    @Operation(summary = "导出工单 CSV")
    @GetMapping("/issues/export")
    public org.springframework.http.ResponseEntity<byte[]> exportIssues(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String dimension) {
        String csv = issueService.exportCsv(status, owner, dimension);
        byte[] body = ("﻿" + csv).getBytes(java.nio.charset.StandardCharsets.UTF_8); // BOM 防 Excel 乱码
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"governance_issues.csv\"")
                .body(body);
    }

}
