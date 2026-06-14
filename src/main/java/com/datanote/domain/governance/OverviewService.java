package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.governance.mapper.DnGovernanceIssueMapper;
import com.datanote.domain.governance.mapper.DnQualityRunMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.governance.model.DnGovernanceIssue;
import com.datanote.domain.governance.model.DnQualityRun;
import com.datanote.domain.metadata.model.DnTableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理总览服务 —— 聚合健康分/资产/质量/工单/敏感分布，供总览大屏一次拉全。
 * recentPassRate 为纯函数（可单测）；各子块独立容错，数据源缺失给 0/空，不抛出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewService {

    private final HealthScoreService healthScoreService;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnQualityRunMapper qualityRunMapper;
    private final DnGovernanceIssueMapper issueMapper;
    private final QualityService qualityService;

    // ========== 纯函数：近期通过率（仅 SUCCESS 且 passRate 非空，均值 *100，保留 1 位） ==========

    public static double recentPassRate(List<DnQualityRun> runs) {
        if (runs == null || runs.isEmpty()) return 0.0;
        double sum = 0.0;
        int n = 0;
        for (DnQualityRun r : runs) {
            if (r == null) continue;
            if (!"success".equalsIgnoreCase(r.getRunStatus())) continue; // run_status 落库为小写 success, 大小写不敏感比对
            if (r.getPassRate() == null) continue;
            sum += r.getPassRate().doubleValue();
            n++;
        }
        if (n == 0) return 0.0;
        return round1(sum / n); // pass_rate 落库即 0-100 百分数, 直接取均值, 不再 *100(原双重放大致 10000%)
    }

    // ========== 聚合 ==========

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("health", health());
        result.put("assets", assets());
        result.put("quality", quality());
        result.put("issues", issues());
        result.put("sensitive", sensitive());
        return result;
    }

    /** 健康分：总分 + 五维分（兼容快照 dimScores 与即时算 dimensions 两种结构） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        double total = 0.0;
        Map<String, Object> dims = new LinkedHashMap<>();
        try {
            Map<String, Object> cur = healthScoreService.current();
            Object t = cur.get("totalScore");
            if (t instanceof Number) total = ((Number) t).doubleValue(); // BigDecimal 亦是 Number, 此一分支即覆盖快照/即时两路
            // 即时算：dimensions = {维度:{score,source}}
            Object dimensions = cur.get("dimensions");
            if (dimensions instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) dimensions).entrySet()) {
                    Object v = e.getValue();
                    if (!(v instanceof Map)) continue;
                    Object score = ((Map<String, Object>) v).get("score"); // 取一次, 避免双重 cast/get
                    if (score instanceof Number) {
                        dims.put(e.getKey(), ((Number) score).doubleValue());
                    }
                }
            }
            // 快照：dimScores = {维度:分}
            if (dims.isEmpty()) {
                Object dimScores = cur.get("dimScores");
                if (dimScores instanceof Map) {
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) dimScores).entrySet()) {
                        if (e.getValue() instanceof Number) {
                            dims.put(e.getKey(), ((Number) e.getValue()).doubleValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("总览健康分取数失败: {}", e.getMessage());
        }
        h.put("total", total);
        h.put("dims", dims);
        return h;
    }

    /** 资产：表数 / 字段数 / 库数(distinct database_name) / 总体量(sum size_bytes) */
    private Map<String, Object> assets() {
        Map<String, Object> a = new LinkedHashMap<>();
        long tableCount = 0, columnCount = 0, dbCount = 0, totalSizeBytes = 0;
        try {
            tableCount = tableMetaMapper.selectCount(null);
        } catch (Exception e) {
            log.warn("总览表数取数失败: {}", e.getMessage());
        }
        try {
            columnCount = columnMetaMapper.selectCount(null);
        } catch (Exception e) {
            log.warn("总览字段数取数失败: {}", e.getMessage());
        }
        try {
            QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
            qw.select("COUNT(DISTINCT database_name) AS c");
            List<Object> r = tableMetaMapper.selectObjs(qw);
            dbCount = asLong(r);
        } catch (Exception e) {
            log.warn("总览库数取数失败: {}", e.getMessage());
        }
        try {
            QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
            qw.select("COALESCE(SUM(size_bytes),0) AS s");
            List<Object> r = tableMetaMapper.selectObjs(qw);
            totalSizeBytes = asLong(r);
        } catch (Exception e) {
            log.warn("总览总体量取数失败: {}", e.getMessage());
        }
        a.put("tableCount", tableCount);
        a.put("columnCount", columnCount);
        a.put("dbCount", dbCount);
        a.put("totalSizeBytes", totalSizeBytes);
        return a;
    }

    /** 质量：整体质量分(QualityService.computeScore 统一口径, 与质量页/首页同数) + 近 24h 运行数 */
    private Map<String, Object> quality() {
        Map<String, Object> q = new LinkedHashMap<>();
        double recentPassRate = 0.0;
        long runs24h = 0;
        try {
            Object s = qualityService.computeScore().get("score");
            if (s instanceof Number) recentPassRate = ((Number) s).doubleValue();
        } catch (Exception e) {
            log.warn("总览整体质量分取数失败: {}", e.getMessage());
        }
        try {
            QueryWrapper<DnQualityRun> qw = new QueryWrapper<>();
            qw.ge("started_at", LocalDateTime.now().minusDays(1));
            runs24h = qualityRunMapper.selectCount(qw);
        } catch (Exception e) {
            log.warn("总览近24h运行数取数失败: {}", e.getMessage());
        }
        q.put("recentPassRate", recentPassRate);
        q.put("runs24h", runs24h);
        return q;
    }

    /** 工单：open / fixing / closed 计数 */
    private Map<String, Object> issues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", issueCount("OPEN"));
        m.put("fixing", issueCount("FIXING"));
        m.put("closed", issueCount("CLOSED"));
        return m;
    }

    private long issueCount(String status) {
        try {
            QueryWrapper<DnGovernanceIssue> qw = new QueryWrapper<>();
            qw.eq("status", status);
            return issueMapper.selectCount(qw);
        } catch (Exception e) {
            log.warn("总览工单计数({})失败: {}", status, e.getMessage());
            return 0;
        }
    }

    /** 敏感：按等级(security_level) 与 类型(sensitive_type) 分组计数 */
    private Map<String, Object> sensitive() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byLevel", groupCount("security_level"));
        m.put("byType", groupCount("sensitive_type"));
        return m;
    }

    /** 对 dn_column_meta 指定列分组计数，过滤空值，返回 {值: 数量} */
    private Map<String, Long> groupCount(String column) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            QueryWrapper<com.datanote.domain.metadata.model.DnColumnMeta> qw = new QueryWrapper<>();
            qw.select(column + " AS k", "COUNT(*) AS cnt")
                    .isNotNull(column).ne(column, "")
                    .groupBy(column).orderByDesc("cnt");
            List<Map<String, Object>> rows = columnMetaMapper.selectMaps(qw);
            if (rows == null) return out;   // selectMaps 理论可返回 null
            for (Map<String, Object> row : rows) {
                if (row == null) continue;
                Object k = row.get("k");
                Object cnt = row.get("cnt");
                if (k == null) continue;
                out.put(String.valueOf(k), cnt instanceof Number ? ((Number) cnt).longValue() : 0L);
            }
        } catch (Exception e) {
            log.warn("总览敏感分组({})失败: {}", column, e.getMessage());
        }
        return out;
    }

    // ========== 工具 ==========

    private static long asLong(List<Object> objs) {
        if (objs == null || objs.isEmpty() || objs.get(0) == null) return 0;
        Object v = objs.get(0);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
