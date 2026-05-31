package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.config.HiveConfig;
import com.datanote.mapper.*;
import com.datanote.model.*;
import com.datanote.util.SensitiveDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 分类分级服务 — 分级模型查询、敏感规则 CRUD、对表采样识别、人工确认打标。
 * 识别下推数仓取样本，识别逻辑全在纯函数 SensitiveDetector。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationService {

    private final DnClassificationLevelMapper levelMapper;
    private final DnSensitiveRuleMapper ruleMapper;
    private final DnLabelAuditMapper auditMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final HiveConfig hiveConfig;

    private static final int SAMPLE_LIMIT = 100;
    private static final String NAME_PATTERN = "[a-zA-Z0-9_]+";

    // ========== 分级模型 ==========

    public List<DnClassificationLevel> levels(String scheme) {
        QueryWrapper<DnClassificationLevel> qw = new QueryWrapper<>();
        if (scheme != null && !scheme.trim().isEmpty()) qw.eq("scheme", scheme.trim());
        qw.orderByAsc("scheme", "sort");
        return levelMapper.selectList(qw);
    }

    /** 敏感分布热力：按表统计已标敏感列数(Top30，含库表名)。 */
    public List<Map<String, Object>> sensitiveHeatmap() {
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.select("table_meta_id AS tid", "COUNT(*) AS cnt")
                .isNotNull("sensitive_type").ne("sensitive_type", "")
                .groupBy("table_meta_id").orderByDesc("cnt").last("LIMIT 30");
        List<Map<String, Object>> rows = columnMetaMapper.selectMaps(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object tid = r.get("tid"); if (tid == null) continue;
            DnTableMeta tm = tableMetaMapper.selectById(((Number) tid).longValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("db", tm != null ? tm.getDatabaseName() : "?");
            m.put("table", tm != null ? tm.getTableName() : ("#" + tid));
            m.put("count", r.get("cnt"));
            out.add(m);
        }
        return out;
    }

    /** 打标审计溯源：按 库.表 返回历次分级变更(最多100条)。 */
    public List<DnLabelAudit> auditTrail(String db, String table) {
        QueryWrapper<DnTableMeta> tq = new QueryWrapper<>();
        tq.eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        DnTableMeta tm = tableMetaMapper.selectOne(tq);
        if (tm == null) return new ArrayList<>();
        QueryWrapper<DnLabelAudit> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tm.getId()).orderByDesc("created_at").last("LIMIT 100");
        return auditMapper.selectList(qw);
    }

    // ========== 敏感规则 CRUD ==========

    public List<DnSensitiveRule> listRules() {
        QueryWrapper<DnSensitiveRule> qw = new QueryWrapper<>();
        qw.orderByAsc("match_type", "id");
        return ruleMapper.selectList(qw);
    }

    public DnSensitiveRule saveRule(DnSensitiveRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getId() == null) {
            if (rule.getEnabled() == null) rule.setEnabled(1);
            rule.setCreatedAt(LocalDateTime.now());
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        return rule;
    }

    public void deleteRule(Long id) {
        ruleMapper.deleteById(id);
    }

    public void toggleRule(Long id) {
        DnSensitiveRule r = ruleMapper.selectById(id);
        if (r == null) return;
        r.setEnabled(r.getEnabled() != null && r.getEnabled() == 1 ? 0 : 1);
        r.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(r);
    }

    private List<DnSensitiveRule> enabledRules() {
        QueryWrapper<DnSensitiveRule> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        return ruleMapper.selectList(qw);
    }

    // ========== 采样识别 ==========

    /**
     * 对数仓指定表采样识别，逐列取样本经 SensitiveDetector 给候选。
     * 返回候选列表：[{column, sensitiveType, suggestLevel, confidence, currentLevel}]
     */
    public List<Map<String, Object>> scanTable(String db, String table) throws SQLException {
        if (db == null || !db.matches(NAME_PATTERN) || table == null || !table.matches(NAME_PATTERN)) {
            throw new SQLException("非法的库名或表名");
        }
        List<DnSensitiveRule> rules = enabledRules();
        List<Map<String, Object>> candidates = new ArrayList<>();

        // 取一批样本行（含全部列），逐列收集样本值
        List<String> headers = new ArrayList<>();
        Map<String, List<String>> colSamples = new LinkedHashMap<>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM `" + db + "`.`" + table + "` LIMIT " + SAMPLE_LIMIT)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            for (int i = 1; i <= cols; i++) {
                String name = md.getColumnName(i);
                if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
                headers.add(name);
                colSamples.put(name, new ArrayList<String>());
            }
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    String v = rs.getString(i);
                    colSamples.get(headers.get(i - 1)).add(v);
                }
            }
        }

        // 已有元数据的当前密级（用于回显）
        Map<String, String> currentLevels = currentLevels(db, table);

        for (String col : headers) {
            SensitiveDetector.Candidate c = SensitiveDetector.detect(col, colSamples.get(col), rules);
            if (c == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("column", col);
            row.put("sensitiveType", c.sensitiveType);
            row.put("suggestLevel", c.suggestLevel);
            row.put("confidence", c.confidence);
            row.put("hitColumnName", c.hitColumnName);
            row.put("currentLevel", currentLevels.get(col));
            candidates.add(row);
        }
        return candidates;
    }

    private Map<String, String> currentLevels(String db, String table) {
        Map<String, String> map = new HashMap<>();
        Long tableMetaId = findTableMetaId(db, table);
        if (tableMetaId == null) return map;
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tableMetaId);
        for (DnColumnMeta cm : columnMetaMapper.selectList(qw)) {
            map.put(cm.getColumnName(), cm.getSecurityLevel());
        }
        return map;
    }

    // ========== 人工确认打标 ==========

    /**
     * 人工确认：回写 dn_column_meta.security_level/sensitive_type + 写 dn_label_audit。
     */
    public void confirm(String db, String table, String column, String newLevel,
                        String sensitiveType, String operator, String reason) {
        Long tableMetaId = getOrCreateTableMetaId(db, table);
        DnColumnMeta cm = findColumnMeta(tableMetaId, column);
        String oldLevel = cm != null ? cm.getSecurityLevel() : null;

        if (cm == null) {
            cm = new DnColumnMeta();
            cm.setTableMetaId(tableMetaId);
            cm.setColumnName(column);
            cm.setSecurityLevel(newLevel);
            cm.setSensitiveType(sensitiveType);
            cm.setCreatedAt(LocalDateTime.now());
            cm.setUpdatedAt(LocalDateTime.now());
            columnMetaMapper.insert(cm);
        } else {
            cm.setSecurityLevel(newLevel);
            cm.setSensitiveType(sensitiveType);
            cm.setUpdatedAt(LocalDateTime.now());
            columnMetaMapper.updateById(cm);
        }

        DnLabelAudit audit = new DnLabelAudit();
        audit.setTableMetaId(tableMetaId);
        audit.setColumnName(column);
        audit.setOldLevel(oldLevel);
        audit.setNewLevel(newLevel);
        audit.setSensitiveType(sensitiveType);
        audit.setOperator(operator != null ? operator : "default");
        audit.setReason(reason);
        audit.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    // ========== 内部 ==========

    private DnColumnMeta findColumnMeta(Long tableMetaId, String column) {
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tableMetaId).eq("column_name", column).last("LIMIT 1");
        return columnMetaMapper.selectOne(qw);
    }

    private Long findTableMetaId(String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
        qw.eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        DnTableMeta meta = tableMetaMapper.selectOne(qw);
        return meta != null ? meta.getId() : null;
    }

    private Long getOrCreateTableMetaId(String db, String table) {
        Long id = findTableMetaId(db, table);
        if (id != null) return id;
        DnTableMeta meta = new DnTableMeta();
        meta.setDatasourceId(0L);
        meta.setDatabaseName(db);
        meta.setTableName(table);
        meta.setCreatedAt(LocalDateTime.now());
        meta.setUpdatedAt(LocalDateTime.now());
        tableMetaMapper.insert(meta);
        return meta.getId();
    }
}
