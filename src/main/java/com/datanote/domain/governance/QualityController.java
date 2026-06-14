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
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量管理 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/quality")
@Tag(name = "数据质量管理", description = "质量规则的增删改查、执行检查、结果查询")
@RequiredArgsConstructor
public class QualityController {

    /** 规则启用状态(status=1 表示启用), 列表/批量/补单统一口径 */
    private static final int STATUS_ENABLED = 1;

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
            // 多用户: 记录创建人(质量工单 owner / QUALITY_ISSUE 通知接收人 据此路由)
            if (rule.getCreatedBy() == null || rule.getCreatedBy().trim().isEmpty()) {
                rule.setCreatedBy(com.datanote.platform.iam.CurrentUserUtil.currentUser());
            }
            if (rule.getStatus() == null) {
                rule.setStatus(STATUS_ENABLED);
            }
            ruleMapper.insert(rule);
        }
        return R.ok(rule);
    }

    /**
     * 批量启停质量规则(status: 1=启用, 0=停用)
     */
    @Operation(summary = "批量启停质量规则")
    @PostMapping("/rules/batch-status")
    public R<Map<String, Object>> batchStatus(@RequestBody Map<String, Object> body) {
        Object idsObj = body == null ? null : body.get("ids");
        Object statusObj = body == null ? null : body.get("status");
        if (!(idsObj instanceof List) || ((List<?>) idsObj).isEmpty()) {
            throw new com.datanote.common.exception.BusinessException("请选择要操作的规则");
        }
        int status;
        try {
            status = statusObj instanceof Number ? ((Number) statusObj).intValue() : Integer.parseInt(String.valueOf(statusObj));
        } catch (NumberFormatException e) {
            throw new com.datanote.common.exception.BusinessException("状态值无效");
        }
        if (status != 0 && status != 1) throw new com.datanote.common.exception.BusinessException("状态只能为 0(停用)或 1(启用)");
        List<Long> ids = new java.util.ArrayList<>();
        for (Object o : (List<?>) idsObj) {
            try { ids.add(Long.valueOf(String.valueOf(o))); } catch (NumberFormatException ignored) {}
        }
        if (ids.isEmpty()) throw new com.datanote.common.exception.BusinessException("规则 ID 无效");
        int n = ruleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DnQualityRule>()
                .in("id", ids).set("status", status).set("updated_at", LocalDateTime.now()));
        Map<String, Object> out = new HashMap<>();
        out.put("updated", n);
        out.put("status", status);
        return R.ok(out);
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
    @Operation(summary = "批量执行质量检查")
    @PostMapping("/run-all")
    public R<String> runAll() {
        // 不加批级 @Transactional: executeRule 逐条独立落库(与单条 run 接口一致),
        // 避免整批 N 次远程慢查询占用同一连接, 也避免任一规则异常回滚掉已成功的执行记录。
        QueryWrapper<DnQualityRule> qw = new QueryWrapper<>();
        qw.eq("status", STATUS_ENABLED);
        List<DnQualityRule> rules = ruleMapper.selectList(qw);
        if (rules == null) rules = new java.util.ArrayList<>();   // selectList 理论可返回 null, 统一兜底
        int count = 0;
        for (DnQualityRule rule : rules) {
            try {
                qualityService.executeRule(rule);
                count++;
            } catch (Exception e) {
                log.warn("批量质检单条规则执行失败 ruleId={}: {}", rule.getId(), e.getMessage());
            }
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
        qw.eq("status", STATUS_ENABLED);
        List<DnQualityRule> rules = ruleMapper.selectList(qw);
        if (rules == null) rules = new java.util.ArrayList<>();   // selectList 理论可返回 null, 统一兜底
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
        if (runs == null) runs = new java.util.ArrayList<>();   // selectList 理论可返回 null, 统一兜底
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
        enabledQw.eq("status", STATUS_ENABLED);
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
