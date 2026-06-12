package com.datanote.domain.governance;

import com.datanote.domain.governance.mapper.DnGovernanceMetricMapper;
import com.datanote.domain.governance.mapper.DnGovernanceScoreMapper;
import com.datanote.domain.governance.mapper.DnQualityRunMapper;
import com.datanote.domain.governance.mapper.DnStandardCheckRunMapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.orchestration.mapper.DnLineageEdgeMapper;
import com.datanote.domain.governance.model.DnGovernanceMetric;
import com.datanote.domain.governance.model.DnGovernanceScore;
import com.datanote.domain.governance.model.DnQualityRun;
import com.datanote.domain.governance.model.DnStandardCheckRun;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.domain.orchestration.model.DnLineageEdge;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 治理健康分服务 —— 五维（规范/质量/安全/生命周期/血缘）加权打分。
 * weightedScore 为纯函数（权重归一化，可单测）；各维度分尽力从已有数据源取，缺失给中性分并标注。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthScoreService {

    private final DnGovernanceMetricMapper metricMapper;
    private final DnGovernanceScoreMapper scoreMapper;
    private final DnStandardCheckRunMapper standardMapper;
    private final DnQualityRunMapper qualityMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnLineageEdgeMapper lineageMapper;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 数据源缺失时的中性分：不惩罚也不奖励 */
    private static final double NEUTRAL = 60.0;

    /** 趋势查询天数默认值与上限（防超大区间扫全表） */
    private static final int TREND_DEFAULT_DAYS = 30;
    private static final int TREND_MAX_DAYS = 3650;

    public static final String DIM_STANDARD = "规范";
    public static final String DIM_QUALITY = "质量";
    public static final String DIM_SECURITY = "安全";
    public static final String DIM_LIFECYCLE = "生命周期";
    public static final String DIM_LINEAGE = "血缘";

    /** 批4#33: 健康分口径版本。v2 = 还原真五维(移除 DCMM 自评汇入, 自评属主观问卷不入客观分), 总分较 v1 会跳变。 */
    public static final String SCORE_VERSION = "v2-五维(2026-06-12 移除组织成熟度汇入)";

    // ========== 纯函数：加权打分（权重归一化，可单测） ==========

    /**
     * 加权求总分。只对同时出现在 dimScores 与 weights 且权重>0 的维度计入，权重按其和归一化。
     * 各维度分先 clamp 到 [0,100]。空输入返回 0。
     */
    public static double weightedScore(Map<String, Double> dimScores, Map<String, Double> weights) {
        if (dimScores == null || weights == null || dimScores.isEmpty() || weights.isEmpty()) {
            return 0.0;
        }
        double sumWeight = 0.0;
        double acc = 0.0;
        for (Map.Entry<String, Double> e : dimScores.entrySet()) {
            Double w = weights.get(e.getKey());
            if (w == null || w <= 0) continue;
            double s = e.getValue() == null ? 0.0 : e.getValue();
            s = Math.max(0.0, Math.min(100.0, s));
            acc += s * w;
            sumWeight += w;
        }
        if (sumWeight <= 0) return 0.0;
        return acc / sumWeight;
    }

    // ========== 维度权重（从配置表按维度汇总） ==========

    /** 各维度权重 = 该维度下启用项 weight 之和 */
    public Map<String, Double> dimensionWeights() {
        Map<String, Double> map = new LinkedHashMap<>();
        QueryWrapper<DnGovernanceMetric> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        List<DnGovernanceMetric> metrics = metricMapper.selectList(qw);
        if (metrics != null) {
            for (DnGovernanceMetric m : metrics) {
                // 维度名为空的脏数据跳过，避免污染权重表的 null/空 key
                if (m == null || m.getDimension() == null || m.getDimension().trim().isEmpty()) continue;
                double w = m.getWeight() == null ? 0.0 : m.getWeight().doubleValue();
                map.merge(m.getDimension(), w, Double::sum);
            }
        }
        // 配置表为空时回退到设计默认权重，保证不崩
        if (map.isEmpty()) {
            map.put(DIM_STANDARD, 20.0);
            map.put(DIM_QUALITY, 25.0);
            map.put(DIM_SECURITY, 25.0);
            map.put(DIM_LIFECYCLE, 15.0);
            map.put(DIM_LINEAGE, 15.0);
        }
        return map;
    }

    // ========== 各维度分（尽力取数，缺失给中性分） ==========

    /** 五维分明细：{维度: {score, source}} */
    public Map<String, Map<String, Object>> dimensionDetail() {
        Map<String, Map<String, Object>> detail = new LinkedHashMap<>();
        detail.put(DIM_STANDARD, standardScore());
        detail.put(DIM_QUALITY, qualityScore());
        detail.put(DIM_SECURITY, securityScore());
        detail.put(DIM_LIFECYCLE, lifecycleScore());
        detail.put(DIM_LINEAGE, lineageScore());
        return detail;
    }

    private Map<String, Object> dim(double score, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", round1(score));
        m.put("source", source);
        return m;
    }

    /** 规范：最近一次落标稽核 pass_rate */
    private Map<String, Object> standardScore() {
        try {
            QueryWrapper<DnStandardCheckRun> qw = new QueryWrapper<>();
            qw.orderByDesc("created_at").last("LIMIT 1");
            DnStandardCheckRun run = standardMapper.selectOne(qw);
            if (run != null && run.getPassRate() != null) {
                return dim(run.getPassRate().doubleValue(), "落标稽核(M7)"); // passRate 已是 0-100(见 StandardService),不可再×100
            }
        } catch (Exception e) {
            log.warn("规范维度取数失败: {}", e.getMessage());
        }
        return dim(NEUTRAL, "无落标记录(中性分)");
    }

    /** 质量：最近 20 次 SUCCESS 的 pass_rate 均值 */
    private Map<String, Object> qualityScore() {
        try {
            QueryWrapper<DnQualityRun> qw = new QueryWrapper<>();
            qw.eq("run_status", "success").isNotNull("pass_rate") // run_status 落库小写, 修大小写不匹配致质量维度恒为空
                    .orderByDesc("finished_at").last("LIMIT 20");
            List<DnQualityRun> runs = qualityMapper.selectList(qw);
            if (runs != null && !runs.isEmpty()) {
                double sum = 0.0;
                int n = 0;
                for (DnQualityRun r : runs) {
                    if (r == null || r.getPassRate() == null) continue; // 防御性判空, 仅对有效记录求均值
                    sum += r.getPassRate().doubleValue();
                    n++;
                }
                // pass_rate 已是百分比(0-100,见 QualityService pass*100/total),不可再×100
                if (n > 0) return dim(sum / n, "质量调度(M5,近" + n + "次)");
            }
        } catch (Exception e) {
            log.warn("质量维度取数失败: {}", e.getMessage());
        }
        return dim(NEUTRAL, "无质量记录(中性分)");
    }

    /** 安全：已分级列 / 总列 覆盖率 */
    private Map<String, Object> securityScore() {
        try {
            long total = columnMetaMapper.selectCount(null);
            if (total > 0) {
                QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
                qw.isNotNull("security_level").ne("security_level", "");
                long graded = columnMetaMapper.selectCount(qw);
                return dim(graded * 100.0 / total, "分级覆盖(M8," + graded + "/" + total + ")");
            }
        } catch (Exception e) {
            log.warn("安全维度取数失败: {}", e.getMessage());
        }
        return dim(NEUTRAL, "无列元数据(中性分)");
    }

    /** 生命周期：有 owner 与 importance 的表占比（资产治理完整度近似） */
    private Map<String, Object> lifecycleScore() {
        try {
            long total = tableMetaMapper.selectCount(null);
            if (total > 0) {
                QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
                qw.isNotNull("owner").ne("owner", "")
                        .isNotNull("importance").ne("importance", "");
                long managed = tableMetaMapper.selectCount(qw);
                return dim(managed * 100.0 / total, "资产治理完整度(" + managed + "/" + total + ")");
            }
        } catch (Exception e) {
            log.warn("生命周期维度取数失败: {}", e.getMessage());
        }
        return dim(NEUTRAL, "无表元数据(中性分)");
    }

    /** 血缘：有血缘边的表 / 总表 覆盖率（去重源表+目标表，按 表名集合 近似） */
    private Map<String, Object> lineageScore() {
        try {
            long total = tableMetaMapper.selectCount(null);
            if (total > 0) {
                Set<String> tablesWithEdge = new HashSet<>();
                // 只取覆盖率计算所需的 4 列, 降低大表全量拉取的内存/IO 开销
                QueryWrapper<DnLineageEdge> qw = new QueryWrapper<>();
                qw.select("src_db", "src_table", "dst_db", "dst_table");
                List<DnLineageEdge> edges = lineageMapper.selectList(qw);
                if (edges != null) {
                    for (DnLineageEdge e : edges) {
                        if (e == null) continue;
                        if (e.getSrcTable() != null) tablesWithEdge.add(key(e.getSrcDb(), e.getSrcTable()));
                        if (e.getDstTable() != null) tablesWithEdge.add(key(e.getDstDb(), e.getDstTable()));
                    }
                }
                long covered = Math.min(tablesWithEdge.size(), total);
                return dim(covered * 100.0 / total, "血缘覆盖(M3/M4," + covered + "/" + total + ")");
            }
        } catch (Exception e) {
            log.warn("血缘维度取数失败: {}", e.getMessage());
        }
        return dim(NEUTRAL, "无表元数据(中性分)");
    }

    private String key(String db, String table) {
        return (db == null ? "" : db) + "." + table;
    }

    // ========== 总分计算 + 快照 ==========

    /** 计算当前各维度分 + 总分（不落库） */
    public Map<String, Object> compute() {
        Map<String, Map<String, Object>> detail = dimensionDetail();
        Map<String, Double> weights = dimensionWeights();
        Map<String, Double> dimScores = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : detail.entrySet()) {
            Map<String, Object> dimMap = e.getValue();
            Object scoreObj = dimMap == null ? null : dimMap.get("score");
            // 维度分缺失或非数值时按 0 处理, 避免 NPE / ClassCastException
            double s = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;
            dimScores.put(e.getKey(), s);
        }
        double total = round1(weightedScore(dimScores, weights));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", total);
        result.put("dimensions", detail);
        result.put("weights", weights);
        result.put("scoreVersion", SCORE_VERSION);   // 批4#33 口径版本标注(v1→v2 总分跳变可解释)
        return result;
    }

    /** 每日 1:30 自动计算并落库健康分快照（趋势数据来源） */
    @Scheduled(cron = "0 30 1 * * ?")
    public void scheduledSnapshot() {
        try {
            computeAndSnapshot();
            log.info("健康分每日快照已生成");
        } catch (Exception e) {
            log.error("健康分每日快照失败: {}", e.getMessage(), e);
        }
    }

    /** 计算并写入时序快照，返回计算结果 */
    public Map<String, Object> computeAndSnapshot() {
        Map<String, Object> result = compute();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> detail =
                    (Map<String, Map<String, Object>>) result.get("dimensions");
            Map<String, Object> dimOnly = new LinkedHashMap<>();
            if (detail != null) {
                for (Map.Entry<String, Map<String, Object>> e : detail.entrySet()) {
                    dimOnly.put(e.getKey(), e.getValue() == null ? null : e.getValue().get("score"));
                }
            }
            Object totalObj = result.get("totalScore");
            double totalVal = totalObj instanceof Number ? ((Number) totalObj).doubleValue() : 0.0;
            DnGovernanceScore snap = new DnGovernanceScore();
            snap.setScoreDate(LocalDate.now());
            snap.setTotalScore(BigDecimal.valueOf(totalVal));
            snap.setDimScores(JSON.writeValueAsString(dimOnly));
            snap.setCreatedAt(LocalDateTime.now());
            scoreMapper.insert(snap);
        } catch (Exception e) {
            log.error("健康分快照写入失败: {}", e.getMessage(), e);
        }
        return result;
    }

    /** 最近一次快照（无则即时计算不落库） */
    public Map<String, Object> current() {
        QueryWrapper<DnGovernanceScore> qw = new QueryWrapper<>();
        qw.orderByDesc("created_at").last("LIMIT 1");
        DnGovernanceScore last = scoreMapper.selectOne(qw);
        if (last != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalScore", last.getTotalScore());
            m.put("scoreDate", last.getScoreDate());
            m.put("dimScores", parseDim(last.getDimScores()));
            m.put("snapshot", true);
            return m;
        }
        Map<String, Object> live = compute();
        live.put("snapshot", false);
        return live;
    }

    /** 趋势：近 days 天快照（按时间升序） */
    public List<Map<String, Object>> trend(int days) {
        // days<=0 用默认 30; 超过上限按上限收口, 防止超大区间扫全表
        int span = days <= 0 ? TREND_DEFAULT_DAYS : Math.min(days, TREND_MAX_DAYS);
        QueryWrapper<DnGovernanceScore> qw = new QueryWrapper<>();
        qw.ge("created_at", LocalDateTime.now().minusDays(span))
                .orderByAsc("created_at");
        List<Map<String, Object>> list = new ArrayList<>();
        List<DnGovernanceScore> snaps = scoreMapper.selectList(qw);
        if (snaps == null) return list;
        for (DnGovernanceScore s : snaps) {
            if (s == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scoreDate", s.getScoreDate());
            m.put("totalScore", s.getTotalScore());
            m.put("createdAt", s.getCreatedAt());
            list.add(m);
        }
        return list;
    }

    private Object parseDim(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            // 历史快照 JSON 解析失败时降级为空, 记录便于定位脏数据
            log.warn("健康分快照维度 JSON 解析失败, 降级空: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
