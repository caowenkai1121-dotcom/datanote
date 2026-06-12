package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import com.datanote.domain.governance.mapper.DnQualityRunMapper;
import com.datanote.domain.governance.model.DnGovernanceIssue;
import com.datanote.domain.governance.model.DnQualityRule;
import com.datanote.domain.governance.model.DnQualityRun;
import com.datanote.common.model.R;
import com.datanote.domain.governance.IssueService;
import com.datanote.domain.governance.QualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量管理 Controller
 */
@RestController
@RequestMapping("/api/quality")
@Tag(name = "数据质量管理", description = "质量规则的增删改查、执行检查、结果查询")
@RequiredArgsConstructor
public class QualityController {

    private final DnQualityRuleMapper ruleMapper;
    private final DnQualityRunMapper runMapper;
    private final QualityService qualityService;
    private final IssueService issueService;
    private final com.datanote.domain.project.ProjectAssetCleaner projectAssetCleaner;   // N4 删除联动清理项目引用

    @Operation(summary = "质量失败根因分析(状态分布+失败样本)")
    @GetMapping("/failure-analysis")
    public R<java.util.Map<String, Object>> failureAnalysis(@RequestParam(required = false) Long ruleId) {
        return R.ok(qualityService.failureAnalysis(ruleId));
    }

    /**
     * 获取所有质量规则
     */
    @Operation(summary = "质量规则列表")
    @GetMapping("/rules")
    public R<List<DnQualityRule>> listRules() {
        QueryWrapper<DnQualityRule> qw = new QueryWrapper<>();
        qw.orderByDesc("updated_at");
        return R.ok(ruleMapper.selectList(qw));
    }

    /**
     * 根据 ID 获取质量规则
     */
    @Operation(summary = "查询质量规则详情")
    @GetMapping("/rule/{id}")
    public R<DnQualityRule> getRule(@PathVariable Long id) {
        DnQualityRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new ResourceNotFoundException("质量规则");
        }
        return R.ok(rule);
    }

    /**
     * 保存质量规则（新增或更新）
     */
    @Operation(summary = "保存质量规则")
    @PostMapping("/rule/save")
    public R<DnQualityRule> saveRule(@RequestBody DnQualityRule rule) {
        if (rule.getId() != null) {
            rule.setUpdatedAt(LocalDateTime.now());
            ruleMapper.updateById(rule);
        } else {
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
            if (rule.getStatus() == null) {
                rule.setStatus(1);
            }
            ruleMapper.insert(rule);
        }
        return R.ok(rule);
    }

    /**
     * 删除质量规则
     */
    @Operation(summary = "删除质量规则")
    @DeleteMapping("/rule/{id}")
    public R<String> deleteRule(@PathVariable Long id) {
        ruleMapper.deleteById(id);
        projectAssetCleaner.onAssetDeleted("QUALITY_RULE", id);
        return R.ok("删除成功");
    }

    /**
     * 手动执行质量检查
     */
    @Operation(summary = "执行质量检查")
    @PostMapping("/rule/{id}/run")
    public R<DnQualityRun> runRule(@PathVariable Long id) {
        DnQualityRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new ResourceNotFoundException("质量规则");
        }
        DnQualityRun result = qualityService.executeRule(rule);
        return R.ok(result);
    }

    /**
     * 批量执行所有启用的质量规则
     */
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "批量执行质量检查")
    @PostMapping("/run-all")
    public R<String> runAll() {
        QueryWrapper<DnQualityRule> qw = new QueryWrapper<>();
        qw.eq("status", 1);
        List<DnQualityRule> rules = ruleMapper.selectList(qw);
        int count = 0;
        for (DnQualityRule rule : rules) {
            qualityService.executeRule(rule);
            count++;
        }
        return R.ok("已执行 " + count + " 条规则");
    }

    /**
     * 规则关联的治理工单(质量→工单反查)
     */
    @Operation(summary = "规则关联的治理工单")
    @GetMapping("/rule/{id}/issues")
    public R<List<DnGovernanceIssue>> ruleIssues(@PathVariable Long id) {
        return R.ok(issueService.listByQualityRule(id));
    }

    /**
     * 为最近一次执行失败/异常的启用规则补建治理工单(运营补单, 历史欠单补齐)
     */
    @Operation(summary = "为失败规则补建治理工单")
    @PostMapping("/issues/sync")
    public R<Map<String, Object>> syncIssues() {
        QueryWrapper<DnQualityRule> qw = new QueryWrapper<>();
        qw.eq("status", 1);
        List<DnQualityRule> rules = ruleMapper.selectList(qw);
        int failing = 0;
        for (DnQualityRule rule : rules) {
            QueryWrapper<DnQualityRun> rq = new QueryWrapper<>();
            rq.eq("rule_id", rule.getId()).orderByDesc("started_at").last("LIMIT 1");
            DnQualityRun last = runMapper.selectOne(rq);
            if (last != null && ("failed".equalsIgnoreCase(last.getRunStatus())
                    || "error".equalsIgnoreCase(last.getRunStatus()))) {
                issueService.raiseQualityIssue(rule, last);
                failing++;
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("rulesScanned", rules.size());
        out.put("failingRules", failing);
        out.put("issuesEnsured", failing);
        return R.ok(out);
    }

    /**
     * 获取指定规则的执行历史
     */
    @Operation(summary = "获取规则执行历史")
    @GetMapping("/rule/{id}/runs")
    public R<List<DnQualityRun>> listRuns(@PathVariable Long id) {
        QueryWrapper<DnQualityRun> qw = new QueryWrapper<>();
        qw.eq("rule_id", id).orderByDesc("started_at").last("LIMIT 20");
        return R.ok(runMapper.selectList(qw));
    }

    /**
     * 规则通过率趋势：近 20 次执行（时间正序，便于前端画折线）
     */
    @Operation(summary = "规则通过率趋势")
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam Long ruleId) {
        QueryWrapper<DnQualityRun> qw = new QueryWrapper<>();
        qw.eq("rule_id", ruleId).orderByDesc("started_at").last("LIMIT 20");
        List<DnQualityRun> runs = runMapper.selectList(qw);
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        // 倒序查出后反转为时间正序
        for (int i = runs.size() - 1; i >= 0; i--) {
            DnQualityRun r = runs.get(i);
            Map<String, Object> p = new HashMap<>();
            p.put("startedAt", r.getStartedAt());
            p.put("passRate", r.getPassRate());
            p.put("runStatus", r.getRunStatus());
            points.add(p);
        }
        return R.ok(points);
    }

    /**
     * 整体质量分：近 7 天 run 的通过率均值（0-100，无数据返回 100）
     */
    @Operation(summary = "整体质量分")
    @GetMapping("/score")
    public R<Map<String, Object>> score() {
        // 口径统一: 质量页/治理总览/首页共用 QualityService.computeScore()
        return R.ok(qualityService.computeScore());
    }

    /**
     * 获取质量概览统计
     */
    @Operation(summary = "质量概览统计")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> data = new HashMap<>();

        long totalRules = ruleMapper.selectCount(null);
        QueryWrapper<DnQualityRule> enabledQw = new QueryWrapper<>();
        enabledQw.eq("status", 1);
        long enabledRules = ruleMapper.selectCount(enabledQw);

        data.put("totalRules", totalRules);
        data.put("enabledRules", enabledRules);

        // 最近24小时的执行统计
        QueryWrapper<DnQualityRun> recentQw = new QueryWrapper<>();
        recentQw.ge("started_at", LocalDateTime.now().minusHours(24));
        List<DnQualityRun> recentRuns = runMapper.selectList(recentQw);

        long totalRuns = recentRuns.size();
        long successRuns = recentRuns.stream().filter(r -> "success".equals(r.getRunStatus())).count();
        long failedRuns = recentRuns.stream().filter(r -> "failed".equals(r.getRunStatus())).count();
        long errorRuns = recentRuns.stream().filter(r -> "error".equals(r.getRunStatus())).count();

        data.put("totalRuns", totalRuns);
        data.put("successRuns", successRuns);
        data.put("failedRuns", failedRuns);
        data.put("errorRuns", errorRuns);

        return R.ok(data);
    }
}
