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
    private final com.datanote.platform.iam.DataAclService dataAclService;   // 数据权限: 过滤/守卫受限指标
    private final com.datanote.platform.ai.vector.SemanticSearchService semanticSearchService;   // 向量语义检索(降级关键字)
    private final com.datanote.platform.ai.vector.VectorIndexService vectorIndexService;   // 增量索引(保存即可检索)

    /**
     * 指标列表（支持关键词搜索和分类筛选）
     */
    @Operation(summary = "指标列表")
    @GetMapping("/list")
    public R<List<DnMetric>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        java.util.Set<String> denied = dataAclService.deniedIds("METRIC");
        boolean hasKw = keyword != null && !keyword.trim().isEmpty();
        if (!hasKw) {
            QueryWrapper<DnMetric> qw = new QueryWrapper<>();
            if (category != null && !category.isEmpty()) qw.eq("category", category);
            qw.orderByDesc("updated_at");
            List<DnMetric> list = metricMapper.selectList(qw);
            if (!denied.isEmpty() && list != null) {
                list.removeIf(m -> m != null && m.getId() != null && denied.contains(String.valueOf(m.getId())));
            }
            return R.ok(list);
        }
        // 关键词搜索: 候选=该分类全部指标(数量小, 内存排序); 向量语义相关在前 + 关键字 LIKE 兜底
        QueryWrapper<DnMetric> base = new QueryWrapper<>();
        if (category != null && !category.isEmpty()) base.eq("category", category);
        List<DnMetric> all = metricMapper.selectList(base);
        if (all == null) all = new java.util.ArrayList<>();
        if (!denied.isEmpty()) all.removeIf(m -> m != null && m.getId() != null && denied.contains(String.valueOf(m.getId())));
        java.util.Map<String, DnMetric> byName = new java.util.LinkedHashMap<>();
        for (DnMetric m : all) if (m != null && m.getMetricName() != null) byName.put(m.getMetricName().toLowerCase(), m);
        List<DnMetric> ordered = new java.util.ArrayList<>();
        java.util.Set<Long> added = new java.util.HashSet<>();
        try {
            Map<String, Object> rag = semanticSearchService.search(keyword.trim(), "metric", 50);
            if (rag != null && "vector".equals(rag.get("engine")) && rag.get("results") instanceof List) {
                for (Object o : (List<?>) rag.get("results")) {
                    if (!(o instanceof Map)) continue;
                    Object nm = ((Map<?, ?>) o).get("name");
                    if (nm == null) continue;
                    DnMetric m = byName.get(String.valueOf(nm).toLowerCase());
                    if (m != null && m.getId() != null && added.add(m.getId())) ordered.add(m);
                }
            }
        } catch (Exception ignore) {
        }
        String kw = keyword.trim().toLowerCase();
        for (DnMetric m : all) {
            if (m == null || m.getId() == null || added.contains(m.getId())) continue;
            String n = m.getMetricName() == null ? "" : m.getMetricName().toLowerCase();
            String c = m.getMetricCode() == null ? "" : m.getMetricCode().toLowerCase();
            String d = m.getDescription() == null ? "" : m.getDescription().toLowerCase();
            if (n.contains(kw) || c.contains(kw) || d.contains(kw)) { ordered.add(m); added.add(m.getId()); }
        }
        return R.ok(ordered);
    }

    /**
     * 指标详情
     */
    @Operation(summary = "指标详情")
    @GetMapping("/{id}")
    public R<DnMetric> getById(@PathVariable Long id) {
        if (!dataAclService.canAccess("METRIC", String.valueOf(id))) {
            return R.fail("无权访问该指标(数据权限受限), 请联系管理员授权");
        }
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
        // metric_code 是预警/取值/关联的引用键, 提供时须唯一(防引用歧义); 启停的 {id,status} 不带 code 则跳过
        String mc = metric.getMetricCode();
        if (mc != null && !mc.trim().isEmpty()) {
            QueryWrapper<DnMetric> dupQ = new QueryWrapper<>();
            dupQ.eq("metric_code", mc.trim());
            if (metric.getId() != null) dupQ.ne("id", metric.getId());
            Long dup = metricMapper.selectCount(dupQ);
            if (dup != null && dup > 0) return R.fail(R.CODE_BAD_REQUEST, "指标编码已存在: " + mc.trim());
        }
        if (metric.getId() != null) {
            // 并发编辑乐观版本校验(状态启停不带 baseUpdatedAt 则跳过)
            if (metric.getBaseUpdatedAt() != null) {
                DnMetric cur = metricMapper.selectById(metric.getId());
                if (cur != null && cur.getUpdatedAt() != null && !metric.getBaseUpdatedAt().equals(cur.getUpdatedAt())) {
                    return R.fail("该指标已被他人修改, 请刷新后重试以免覆盖对方改动");
                }
            }
            metric.setUpdatedAt(LocalDateTime.now());
            metricMapper.updateById(metric);
        } else {
            // 新建必须有指标名(更新分支可只传 {id,status} 做启停, 故仅在此校验)
            if (metric.getMetricName() == null || metric.getMetricName().trim().isEmpty()) {
                return R.fail(R.CODE_BAD_REQUEST, "指标名称不能为空");
            }
            metric.setCreatedAt(LocalDateTime.now());
            metric.setUpdatedAt(LocalDateTime.now());
            // 多用户: 负责人未填时默认创建者本人(指标预警/通知据此路由)
            if (metric.getOwner() == null || metric.getOwner().trim().isEmpty()) {
                metric.setOwner(com.datanote.platform.iam.CurrentUserUtil.currentUser());
            }
            if (metric.getStatus() == null) {
                metric.setStatus(1);
            }
            metricMapper.insert(metric);
        }
        // 增量索引: 保存后即可被语义检索/查重(异步, 向量不可用静默跳过)
        vectorIndexService.indexEntityAsync("metric", metric.getId(), metric.getMetricName(),
                metric.getDescription(), metricText(metric));
        return R.ok(metric);
    }

    /** 指标嵌入文本: 名称 + 编码 + 说明(与全量索引口径一致)。 */
    private static String metricText(DnMetric m) {
        StringBuilder sb = new StringBuilder("指标 ").append(m.getMetricName() == null ? "" : m.getMetricName());
        if (m.getMetricCode() != null && !m.getMetricCode().trim().isEmpty()) sb.append("(").append(m.getMetricCode()).append(")");
        if (m.getDescription() != null && !m.getDescription().trim().isEmpty()) sb.append(" 说明:").append(m.getDescription());
        return sb.toString();
    }

    /** 语义查重: 建/改指标时按名称+说明召回相似已有指标(相关度>0.6), 供前端提示"疑似重复, 考虑复用"(不阻断)。 */
    @GetMapping("/dedup-check")
    public R<List<Map<String, Object>>> dedupCheck(@RequestParam(required = false) String keyword) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) return R.ok(out);
        try {
            Map<String, Object> rag = semanticSearchService.search(keyword.trim(), "metric", 5);
            if (rag != null && "vector".equals(rag.get("engine")) && rag.get("results") instanceof List) {
                for (Object o : (List<?>) rag.get("results")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> h = (Map<?, ?>) o;
                    Object score = h.get("score");
                    double sc = 0;
                    try { sc = score == null ? 0 : Double.parseDouble(String.valueOf(score)); } catch (Exception ig) {}
                    if (sc < 0.6) continue;   // 仅较相似的才作为查重提示
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", h.get("name"));
                    m.put("title", h.get("title"));
                    m.put("score", score);
                    out.add(m);
                }
            }
        } catch (Exception ignore) {
        }
        return R.ok(out);
    }

    /**
     * 删除指标（级联清理: 关联/预警规则/值快照/未关闭工单/项目绑定——停用保历史, 删除彻底清）
     */
    @Operation(summary = "删除指标(级联清理引用)")
    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public R<String> delete(@PathVariable Long id) {
        metricMapper.deleteById(id);
        vectorIndexService.deleteEntityAsync("metric", id);   // 清理该指标索引点, 防孤儿向量
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
