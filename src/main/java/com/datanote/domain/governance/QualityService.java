package com.datanote.domain.governance;

import com.datanote.domain.metadata.DataMapService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.config.HiveConfig;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.governance.mapper.DnQualityRunMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.governance.model.DnQualityRule;
import com.datanote.domain.governance.model.DnQualityRun;
import com.datanote.common.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据质量检查服务 — 根据规则对数据源执行质量校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityService {

    private final DnDatasourceMapper datasourceMapper;
    private final DnQualityRunMapper qualityRunMapper;
    private final ObjectMapper objectMapper;
    private final HiveConfig hiveConfig;
    private final IssueService issueService;

    /** 合法标识符：字母/数字/下划线/中文，1-128 字符 */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[\\w\\u4e00-\\u9fa5]{1,128}$");

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    /**
     * 质量失败根因分析：聚合近 100 次执行的状态分布 + 最近失败/错误样本(Top20)。
     * ruleId 为空则跨全部规则。
     */
    public Map<String, Object> failureAnalysis(Long ruleId) {
        QueryWrapper<DnQualityRun> qw = new QueryWrapper<>();
        if (ruleId != null) qw.eq("rule_id", ruleId);
        qw.orderByDesc("started_at").last("LIMIT 100");
        List<DnQualityRun> runs = qualityRunMapper.selectList(qw);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (DnQualityRun r : runs) {
            String st = r.getRunStatus() == null ? "UNKNOWN" : r.getRunStatus();
            byStatus.merge(st, 1, Integer::sum);
            if (("FAIL".equalsIgnoreCase(st) || "ERROR".equalsIgnoreCase(st)) && failures.size() < 20) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ruleId", r.getRuleId());
                m.put("startedAt", r.getStartedAt());
                m.put("runStatus", st);
                m.put("passRate", r.getPassRate());
                m.put("failCount", r.getFailCount());
                m.put("errorMsg", r.getErrorMsg());
                m.put("errorSample", r.getErrorSample());
                failures.add(m);
            }
        }
        List<Map<String, Object>> statusList = new ArrayList<>();
        byStatus.forEach((k, v) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("status", k); m.put("cnt", v); statusList.add(m); });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCounts", statusList);
        out.put("recentFailures", failures);
        out.put("totalRuns", runs.size());
        return out;
    }

    /**
     * 校验 SQL 标识符（表名、列名），防止 SQL 注入
     */
    private static void validateIdentifier(String name, String label) {
        if (name == null || !IDENTIFIER_PATTERN.matcher(name).matches()) {
            throw new BusinessException(label + " 包含非法字符: " + name);
        }
    }

    /**
     * 执行单条质量规则检查
     *
     * @param rule 质量规则
     * @return 检查执行记录
     */
    public DnQualityRun executeRule(DnQualityRule rule) {
        DnQualityRun run = new DnQualityRun();
        run.setRuleId(rule.getId());
        run.setStartedAt(LocalDateTime.now());

        long startMs = System.currentTimeMillis();
        try {
            validateIdentifier(rule.getDatabaseName(), "数据库名");
            try (Connection conn = openConnection(rule)) {
                String origCatalog = null;
                try {
                    origCatalog = conn.getCatalog();
                    conn.setCatalog(rule.getDatabaseName());
                } catch (SQLException ignore) {
                    // 个别驱动不支持切库，忽略，依赖 SQL 中的库定位
                }
                try {
                    executeCheck(conn, rule, run);
                } finally {
                    if (origCatalog != null) {
                        try { conn.setCatalog(origCatalog); } catch (SQLException ignore) { /* 还原失败不影响结果 */ }
                    }
                }
            }
        } catch (Exception e) {
            run.setRunStatus("error");
            run.setErrorMsg(e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            log.error("质量检查执行异常 ruleId={}", rule.getId(), e);
        }

        long elapsed = System.currentTimeMillis() - startMs;
        run.setDurationMs(elapsed);
        run.setFinishedAt(LocalDateTime.now());
        qualityRunMapper.insert(run);
        // 治理闭环接点①：失败/异常自动生成治理工单(内部兜底，不影响本执行结果)
        issueService.raiseQualityIssue(rule, run);
        return run;
    }

    /**
     * 按规则目标打开连接：数仓走 Doris 连接池，源库走 DriverManager。
     */
    private Connection openConnection(DnQualityRule rule) throws SQLException {
        if (isWarehouseTarget(rule)) {
            return hiveConfig.getConnection();
        }
        DnDatasource ds = datasourceMapper.selectById(rule.getDatasourceId());
        if (ds == null) {
            throw new BusinessException("数据源不存在: " + rule.getDatasourceId());
        }
        String password = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        String url = buildSourceJdbcUrl(ds.getHost(), ds.getPort(), rule.getDatabaseName());
        return DriverManager.getConnection(url, ds.getUsername(), password);
    }

    /** datasourceId 为 null 或 0 表示目标是 Doris 数仓（约定与 DataMapService 一致） */
    static boolean isWarehouseTarget(DnQualityRule rule) {
        Long id = rule.getDatasourceId();
        return id == null || id == 0L;
    }

    /** 构建源库 JDBC URL（MySQL 协议，Doris/StarRocks 亦兼容） */
    static String buildSourceJdbcUrl(String host, Integer port, String db) {
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
    }

    private void executeCheck(Connection conn, DnQualityRule rule, DnQualityRun run) throws Exception {
        String ruleType = rule.getRuleType();
        switch (ruleType) {
            case "null_check":
                executeNullCheck(conn, rule, run);
                break;
            case "unique_check":
                executeUniqueCheck(conn, rule, run);
                break;
            case "value_range":
                executeValueRangeCheck(conn, rule, run);
                break;
            case "regex_check":
                executeRegexCheck(conn, rule, run);
                break;
            case "custom_sql":
                executeCustomSqlCheck(conn, rule, run);
                break;
            default:
                throw new BusinessException("不支持的规则类型: " + ruleType);
        }
    }

    /**
     * 空值检查：统计指定字段为 NULL 或空字符串的记录数
     */
    private void executeNullCheck(Connection conn, DnQualityRule rule, DnQualityRun run) throws Exception {
        String table = rule.getTableName();
        String column = rule.getColumnName();
        validateIdentifier(table, "表名");
        validateIdentifier(column, "列名");

        String totalSql = "SELECT COUNT(*) FROM `" + table + "`";
        String failSql = "SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` IS NULL OR `" + column + "` = ''";
        String sampleSql = "SELECT * FROM `" + table + "` WHERE `" + column + "` IS NULL OR `" + column + "` = '' LIMIT 10";

        run.setExecSql(failSql);
        long total = queryCount(conn, totalSql);
        long fail = queryCount(conn, failSql);

        fillRunResult(run, rule, total, total - fail, fail);
        if (fail > 0) {
            run.setErrorSample(querySampleJson(conn, sampleSql));
        }
    }

    /**
     * 唯一性检查：统计指定字段的重复记录数
     */
    private void executeUniqueCheck(Connection conn, DnQualityRule rule, DnQualityRun run) throws Exception {
        String table = rule.getTableName();
        String column = rule.getColumnName();
        validateIdentifier(table, "表名");
        validateIdentifier(column, "列名");

        String totalSql = "SELECT COUNT(*) FROM `" + table + "`";
        String failSql = "SELECT COUNT(*) FROM (SELECT `" + column + "` FROM `" + table
                + "` GROUP BY `" + column + "` HAVING COUNT(*) > 1) t";
        String sampleSql = "SELECT `" + column + "`, COUNT(*) as cnt FROM `" + table
                + "` GROUP BY `" + column + "` HAVING COUNT(*) > 1 LIMIT 10";

        run.setExecSql(failSql);
        long total = queryCount(conn, totalSql);
        long failGroups = queryCount(conn, failSql);

        fillRunResult(run, rule, total, total - failGroups, failGroups);
        if (failGroups > 0) {
            run.setErrorSample(querySampleJson(conn, sampleSql));
        }
    }

    /**
     * 值域检查：统计不在指定范围内的记录数
     */
    private void executeValueRangeCheck(Connection conn, DnQualityRule rule, DnQualityRun run) throws Exception {
        String table = rule.getTableName();
        String column = rule.getColumnName();
        validateIdentifier(table, "表名");
        validateIdentifier(column, "列名");

        Map<String, Object> config = parseConfig(rule.getRuleConfig());
        Object minVal = config.get("min");
        Object maxVal = config.get("max");

        // 校验范围值必须是数字，防止注入
        if (minVal != null && !(minVal instanceof Number)) {
            throw new BusinessException("value_range 的 min 必须是数字");
        }
        if (maxVal != null && !(maxVal instanceof Number)) {
            throw new BusinessException("value_range 的 max 必须是数字");
        }

        StringBuilder condition = new StringBuilder();
        if (minVal != null) {
            condition.append("`").append(column).append("` < ").append(minVal);
        }
        if (maxVal != null) {
            if (condition.length() > 0) condition.append(" OR ");
            condition.append("`").append(column).append("` > ").append(maxVal);
        }
        if (condition.length() == 0) {
            condition.append("1=0");
        }

        String totalSql = "SELECT COUNT(*) FROM `" + table + "`";
        String failSql = "SELECT COUNT(*) FROM `" + table + "` WHERE " + condition;
        String sampleSql = "SELECT * FROM `" + table + "` WHERE " + condition + " LIMIT 10";

        run.setExecSql(failSql);
        long total = queryCount(conn, totalSql);
        long fail = queryCount(conn, failSql);

        fillRunResult(run, rule, total, total - fail, fail);
        if (fail > 0) {
            run.setErrorSample(querySampleJson(conn, sampleSql));
        }
    }

    /**
     * 正则检查：统计不匹配指定正则表达式的记录数
     */
    private void executeRegexCheck(Connection conn, DnQualityRule rule, DnQualityRun run) throws Exception {
        String table = rule.getTableName();
        String column = rule.getColumnName();
        validateIdentifier(table, "表名");
        validateIdentifier(column, "列名");

        Map<String, Object> config = parseConfig(rule.getRuleConfig());
        String regexPattern = (String) config.get("pattern");
        if (regexPattern == null || regexPattern.isEmpty()) {
            throw new BusinessException("regex_check 规则缺少 pattern 配置");
        }

        String totalSql = "SELECT COUNT(*) FROM `" + table + "`";
        // 使用 PreparedStatement 参数化正则表达式，防止注入
        String failSql = "SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` NOT REGEXP ?";
        String sampleSql = "SELECT * FROM `" + table + "` WHERE `" + column + "` NOT REGEXP ? LIMIT 10";

        run.setExecSql("SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` NOT REGEXP '" + regexPattern + "'");
        long total = queryCount(conn, totalSql);
        long fail = queryCountWithParam(conn, failSql, regexPattern);

        fillRunResult(run, rule, total, total - fail, fail);
        if (fail > 0) {
            run.setErrorSample(querySampleJsonWithParam(conn, sampleSql, regexPattern));
        }
    }

    /** SQL 注释模式：单行注释 -- 和多行注释 */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 校验 SQL 是否为只读查询（仅允许 SELECT 开头）
     */
    private static void validateReadOnlySql(String sql) {
        // 去除注释
        String cleaned = LINE_COMMENT.matcher(sql).replaceAll(" ");
        cleaned = BLOCK_COMMENT.matcher(cleaned).replaceAll(" ");
        // 去除前后空白
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            throw new BusinessException("custom_sql 不能为空");
        }
        if (!cleaned.toUpperCase().startsWith("SELECT")) {
            throw new BusinessException("custom_sql 只允许 SELECT 查询");
        }
    }

    /**
     * 自定义SQL检查：执行用户提供的SQL，约定返回 total_count 和 fail_count 两列
     */
    private void executeCustomSqlCheck(Connection conn, DnQualityRule rule, DnQualityRun run) throws Exception {
        String sql = rule.getCustomSql();
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException("custom_sql 规则缺少 SQL 配置");
        }

        // 只读校验：仅允许 SELECT
        validateReadOnlySql(sql);

        run.setExecSql(sql);

        // 双重保护：设置连接为只读 + 禁止自动提交
        boolean origReadOnly = conn.isReadOnly();
        boolean origAutoCommit = conn.getAutoCommit();
        try {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(60);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    boolean hasTotal = false, hasFail = false;
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String label = md.getColumnLabel(i);
                        if ("total_count".equalsIgnoreCase(label)) hasTotal = true;
                        else if ("fail_count".equalsIgnoreCase(label)) hasFail = true;
                    }
                    if (!hasTotal || !hasFail) {
                        throw new BusinessException("自定义SQL结果集必须包含 total_count 和 fail_count 两列");
                    }
                    if (rs.next()) {
                        long total = rs.getLong("total_count");
                        long fail = rs.getLong("fail_count");
                        fillRunResult(run, rule, total, total - fail, fail);
                    } else {
                        fillRunResult(run, rule, 0, 0, 0);
                    }
                }
            }
        } finally {
            conn.setReadOnly(origReadOnly);
            conn.setAutoCommit(origAutoCommit);
        }
    }

    private void fillRunResult(DnQualityRun run, DnQualityRule rule, long total, long pass, long fail) {
        run.setTotalCount(total);
        run.setPassCount(pass);
        run.setFailCount(fail);
        BigDecimal rate;
        if (total > 0) {
            rate = BigDecimal.valueOf(pass * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
        } else {
            rate = BigDecimal.valueOf(100);
        }
        run.setPassRate(rate);
        run.setRunStatus(judgeStatus(rate, rule.getPassThreshold()));
    }

    /**
     * 按阈值判定状态：通过率 >= 阈值即 success，否则 failed。阈值为 null 视为 100（须满分才过）。
     */
    static String judgeStatus(BigDecimal passRate, BigDecimal threshold) {
        BigDecimal th = threshold != null ? threshold : BigDecimal.valueOf(100);
        return passRate.compareTo(th) >= 0 ? "success" : "failed";
    }

    private long queryCount(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(60);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private long queryCountWithParam(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private String querySampleJsonWithParam(Connection conn, String sql, String param) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<Map<String, Object>> samples = new ArrayList<>();
                while (rs.next() && samples.size() < 10) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    samples.add(row);
                }
                return objectMapper.writeValueAsString(samples);
            }
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private String querySampleJson(Connection conn, String sql) {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(60);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<Map<String, Object>> samples = new ArrayList<>();
                while (rs.next() && samples.size() < 10) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    samples.add(row);
                }
                return objectMapper.writeValueAsString(samples);
            }
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String configJson) throws Exception {
        if (configJson == null || configJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(configJson, Map.class);
    }

}
