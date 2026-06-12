package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.ResourceNotFoundException;
import com.datanote.domain.governance.mapper.DnMetricMapper;
import com.datanote.domain.governance.mapper.DnMetricRefMapper;
import com.datanote.domain.governance.model.DnMetric;
import com.datanote.domain.governance.model.DnMetricRef;
import com.datanote.common.model.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 指标管理 Controller
 */
@RestController
@RequestMapping("/api/metric")
@Tag(name = "指标管理", description = "业务指标的定义、分类、搜索")
@RequiredArgsConstructor
public class MetricController {

    private final DnMetricMapper metricMapper;
    private final DnMetricRefMapper metricRefMapper;
    private final com.datanote.domain.consumption.mapper.DnMetricAlertRuleMapper alertRuleMapper;
    private final com.datanote.domain.consumption.mapper.DnMetricValueMapper metricValueMapper;
    private final com.datanote.domain.governance.mapper.DnGovernanceIssueMapper issueMapper;
    private final IssueService issueService;
    private final com.datanote.domain.project.ProjectAssetCleaner projectAssetCleaner;

    /**
     * 指标列表（支持关键词搜索和分类筛选）
     */
    @Operation(summary = "指标列表")
    @GetMapping("/list")
    public R<List<DnMetric>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        QueryWrapper<DnMetric> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("metric_name", keyword)
                    .or().like("metric_code", keyword)
                    .or().like("description", keyword));
        }
        if (category != null && !category.isEmpty()) {
            qw.eq("category", category);
        }
        qw.orderByDesc("updated_at");
        return R.ok(metricMapper.selectList(qw));
    }

    /**
     * 指标详情
     */
    @Operation(summary = "指标详情")
    @GetMapping("/{id}")
    public R<DnMetric> getById(@PathVariable Long id) {
        DnMetric metric = metricMapper.selectById(id);
        if (metric == null) {
            throw new ResourceNotFoundException("指标");
        }
        return R.ok(metric);
    }

    /**
     * 保存指标
     */
    @Operation(summary = "保存指标")
    @PostMapping("/save")
    public R<DnMetric> save(@RequestBody DnMetric metric) {
        if (metric.getId() != null) {
            metric.setUpdatedAt(LocalDateTime.now());
            metricMapper.updateById(metric);
        } else {
            metric.setCreatedAt(LocalDateTime.now());
            metric.setUpdatedAt(LocalDateTime.now());
            if (metric.getStatus() == null) {
                metric.setStatus(1);
            }
            metricMapper.insert(metric);
        }
        return R.ok(metric);
    }

    /**
     * 删除指标（级联清理: 关联/预警规则/值快照/未关闭工单/项目绑定——停用保历史, 删除彻底清）
     */
    @Operation(summary = "删除指标(级联清理引用)")
    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<String> delete(@PathVariable Long id) {
        metricMapper.deleteById(id);
        metricRefMapper.delete(new QueryWrapper<DnMetricRef>().eq("metric_id", id));
        alertRuleMapper.delete(new QueryWrapper<com.datanote.domain.consumption.model.DnMetricAlertRule>().eq("metric_id", id));
        metricValueMapper.delete(new QueryWrapper<com.datanote.domain.consumption.model.DnMetricValue>().eq("metric_id", id));
        // 关闭该指标未闭环工单(objectRef=metric:{metricId}), 附删除备注
        try {
            List<com.datanote.domain.governance.model.DnGovernanceIssue> issues = issueMapper.selectList(
                    new QueryWrapper<com.datanote.domain.governance.model.DnGovernanceIssue>()
                            .eq("issue_type", "METRIC").eq("object_ref", "metric:" + id).ne("status", "CLOSED"));
            if (issues != null) {
                for (com.datanote.domain.governance.model.DnGovernanceIssue iss : issues) {
                    try {
                        // 沿状态机最短路径关单: OPEN→CLOSED 直达, 其余 →FIXING→OPEN→CLOSED
                        String st = iss.getStatus();
                        if (!"OPEN".equals(st)) {
                            if (!"FIXING".equals(st)) issueService.transition(iss.getId(), "FIXING", "metric-delete");
                            issueService.transition(iss.getId(), "OPEN", "metric-delete");
                        }
                        issueService.transition(iss.getId(), "CLOSED", "metric-delete");
                        // 只补备注(整对象回写会用旧 status 覆盖 CLOSED)
                        issueMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.datanote.domain.governance.model.DnGovernanceIssue>()
                                .eq("id", iss.getId())
                                .set("description", "[指标已删除] " + (iss.getDescription() == null ? "" : iss.getDescription())));
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        projectAssetCleaner.onAssetDeleted("METRIC", id);
        return R.ok("删除成功");
    }

    /**
     * 获取所有指标分类
     */
    @Operation(summary = "指标分类列表")
    @GetMapping("/categories")
    public R<List<String>> categories() {
        List<DnMetric> all = metricMapper.selectList(null);
        List<String> cats = all.stream()
                .map(DnMetric::getCategory)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return R.ok(cats);
    }

    // (原 /stats 端点已清退: 前端无消费, 概览数字由消费层 overview 提供)

    // ==================== 指标-资产关联 ====================

    /**
     * 查询指标关联的资产
     */
    @Operation(summary = "指标关联列表")
    @GetMapping("/{id}/refs")
    public R<List<DnMetricRef>> listRefs(@PathVariable Long id) {
        QueryWrapper<DnMetricRef> qw = new QueryWrapper<>();
        qw.eq("metric_id", id).orderByAsc("id");
        return R.ok(metricRefMapper.selectList(qw));
    }

    /**
     * 新增指标-资产关联
     */
    @Operation(summary = "新增指标关联")
    @PostMapping("/{id}/refs")
    public R<DnMetricRef> addRef(@PathVariable Long id, @RequestBody DnMetricRef ref) {
        if (metricMapper.selectById(id) == null) {
            throw new ResourceNotFoundException("指标");
        }
        ref.setMetricId(id);
        ref.setId(null);
        if (ref.getRefType() == null || ref.getRefType().isEmpty()) {
            ref.setRefType("SOURCE");
        }
        ref.setCreatedAt(LocalDateTime.now());
        metricRefMapper.insert(ref);
        return R.ok(ref);
    }

    /**
     * 删除指标-资产关联
     */
    @Operation(summary = "删除指标关联")
    @DeleteMapping("/refs/{refId}")
    public R<String> deleteRef(@PathVariable Long refId) {
        metricRefMapper.deleteById(refId);
        return R.ok("删除成功");
    }
}
