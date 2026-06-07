package com.datanote.domain.governance;

import com.datanote.domain.governance.mapper.DnClassificationLevelMapper;
import com.datanote.domain.governance.mapper.DnSensitiveRuleMapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.platform.audit.mapper.DnLabelAuditMapper;
import com.datanote.domain.governance.model.DnClassificationLevel;
import com.datanote.domain.governance.model.DnSensitiveRule;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.audit.model.DnLabelAudit;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.platform.config.HiveConfig;
import com.datanote.domain.governance.util.SensitiveDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    /** 表级敏感标签（写入 dn_table_meta.tags，CSV 逗号分隔） */
    static final String SENSITIVE_TAG = "含敏感字段";

    /**
     * 幂等增/删敏感标签：tags 为逗号/中文逗号分隔的 CSV。
     * hasSensitive=true 确保含 SENSITIVE_TAG（去重），false 则移除；其余标签保序保留。
     */
    static String applySensitiveTag(String tags, boolean hasSensitive) {
        List<String> kept = new ArrayList<>();
        if (tags != null) {
            for (String t : tags.split("[,，]")) {
                String s = t.trim();
                if (!s.isEmpty() && !s.equals(SENSITIVE_TAG)) kept.add(s);
            }
        }
        if (hasSensitive) kept.add(SENSITIVE_TAG);
        return String.join(",", kept);
    }

    // ========== 分级模型 ==========

    public List<DnClassificationLevel> levels(String scheme) {
        QueryWrapper<DnClassificationLevel> qw = new QueryWrapper<>();
        if (scheme != null && !scheme.trim().isEmpty()) qw.eq("scheme", scheme.trim());
        qw.orderByAsc("scheme", "sort");
        List<DnClassificationLevel> list = levelMapper.selectList(qw);
        return list != null ? list : new ArrayList<>();
    }

    /** 敏感分布热力：按表统计已标敏感列数(Top30，含库表名)。 */
    public List<Map<String, Object>> sensitiveHeatmap() {
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.select("table_meta_id AS tid", "COUNT(*) AS cnt")
                .isNotNull("sensitive_type").ne("sensitive_type", "")
                .groupBy("table_meta_id").orderByDesc("cnt").last("LIMIT 30");
        List<Map<String, Object>> rows = columnMetaMapper.selectMaps(qw);
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        // 先收集本批次的 tableMetaId，一次性批量取表元数据，消除循环内 selectById(N+1)
        List<Long> tids = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object tid = (r == null) ? null : r.get("tid");
            if (tid instanceof Number) tids.add(((Number) tid).longValue());
        }
        Map<Long, DnTableMeta> metaMap = batchLoadTableMeta(tids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object tid = (r == null) ? null : r.get("tid");
            if (!(tid instanceof Number)) continue;
            DnTableMeta tm = metaMap.get(((Number) tid).longValue());
            if (tm == null) continue; // 元数据已删的脏行跳过,不返回无法定位的虚表名
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("db", tm.getDatabaseName());
            m.put("table", tm.getTableName());
            m.put("count", r.get("cnt"));
            out.add(m);
        }
        return out;
    }

    /** 按 id 批量加载表元数据并建 id→meta 映射(空入参返回空映射，避免 selectBatchIds 空集报错)。 */
    private Map<Long, DnTableMeta> batchLoadTableMeta(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        List<DnTableMeta> metas = tableMetaMapper.selectBatchIds(ids);
        if (metas == null) return Collections.emptyMap();
        Map<Long, DnTableMeta> map = new HashMap<>();
        for (DnTableMeta m : metas) {
            if (m != null && m.getId() != null) map.put(m.getId(), m);
        }
        return map;
    }

    /** 打标审计溯源：按 库.表 返回历次分级变更(最多100条)。 */
    public List<DnLabelAudit> auditTrail(String db, String table) {
        if (db == null || db.trim().isEmpty()) throw new BusinessException("库名不能为空");
        if (table == null || table.trim().isEmpty()) throw new BusinessException("表名不能为空");
        QueryWrapper<DnTableMeta> tq = new QueryWrapper<>();
        tq.eq("database_name", db.trim()).eq("table_name", table.trim()).last("LIMIT 1");
        DnTableMeta tm = tableMetaMapper.selectOne(tq);
        if (tm == null || tm.getId() == null) return new ArrayList<>();
        QueryWrapper<DnLabelAudit> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tm.getId()).orderByDesc("created_at").last("LIMIT 100");
        List<DnLabelAudit> list = auditMapper.selectList(qw);
        return list != null ? list : new ArrayList<>();
    }

    // ========== 敏感规则 CRUD ==========

    public List<DnSensitiveRule> listRules() {
        QueryWrapper<DnSensitiveRule> qw = new QueryWrapper<>();
        qw.orderByAsc("match_type", "id");
        List<DnSensitiveRule> list = ruleMapper.selectList(qw);
        return list != null ? list : new ArrayList<>();
    }

    public DnSensitiveRule saveRule(DnSensitiveRule rule) {
        if (rule == null) throw new BusinessException("规则不能为空");
        if (rule.getRuleName() == null || rule.getRuleName().trim().isEmpty()) {
            throw new BusinessException("规则名不能为空");
        }
        if (rule.getMatchType() == null || rule.getMatchType().trim().isEmpty()) {
            throw new BusinessException("匹配方式不能为空");
        }
        if (rule.getPattern() == null || rule.getPattern().trim().isEmpty()) {
            throw new BusinessException("匹配模式不能为空");
        }
        if (rule.getSensitiveType() == null || rule.getSensitiveType().trim().isEmpty()) {
            throw new BusinessException("敏感类型不能为空");
        }
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
        if (id == null) throw new BusinessException("规则ID不能为空");
        ruleMapper.deleteById(id);
    }

    public void toggleRule(Long id) {
        if (id == null) throw new BusinessException("规则ID不能为空");
        DnSensitiveRule r = ruleMapper.selectById(id);
        if (r == null) throw new BusinessException("规则不存在: id=" + id);
        r.setEnabled(r.getEnabled() != null && r.getEnabled() == 1 ? 0 : 1);
        r.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(r);
    }

    private List<DnSensitiveRule> enabledRules() {
        QueryWrapper<DnSensitiveRule> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        List<DnSensitiveRule> list = ruleMapper.selectList(qw);
        return list != null ? list : new ArrayList<>();
    }

    // ========== 采样识别 ==========

    /**
     * 对数仓指定表采样识别，逐列取样本经 SensitiveDetector 给候选。
     * 返回候选列表：[{column, sensitiveType, suggestLevel, confidence, currentLevel}]
     */
    public List<Map<String, Object>> scanTable(String db, String table) throws SQLException {
        if (db == null || db.trim().isEmpty()) throw new BusinessException("库名不能为空");
        if (table == null || table.trim().isEmpty()) throw new BusinessException("表名不能为空");
        // 仅允许字母/数字/下划线，杜绝 SQL 注入（库表名拼入 SQL，不能走参数化）
        if (!db.matches(NAME_PATTERN)) throw new BusinessException("非法库名(仅允许字母数字下划线): " + db);
        if (!table.matches(NAME_PATTERN)) throw new BusinessException("非法表名(仅允许字母数字下划线): " + table);
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
                if (name == null) name = "col" + i; // 元数据列名缺失时兜底，避免后续判空/NPE
                if (name.contains(".")) name = name.substring(name.lastIndexOf('.') + 1);
                headers.add(name);
                colSamples.put(name, new ArrayList<String>());
            }
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    String v = rs.getString(i);
                    List<String> bucket = colSamples.get(headers.get(i - 1));
                    if (bucket != null) bucket.add(v);
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
        List<DnColumnMeta> cols = columnMetaMapper.selectList(qw);
        if (cols == null) return map;
        for (DnColumnMeta cm : cols) {
            if (cm != null && cm.getColumnName() != null) map.put(cm.getColumnName(), cm.getSecurityLevel());
        }
        return map;
    }

    // ========== 人工确认打标 ==========

    /**
     * 人工确认：回写 dn_column_meta.security_level/sensitive_type + 写 dn_label_audit。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String db, String table, String column, String newLevel,
                        String sensitiveType, String operator, String reason) {
        if (db == null || db.trim().isEmpty()) throw new BusinessException("库名不能为空");
        if (table == null || table.trim().isEmpty()) throw new BusinessException("表名不能为空");
        if (column == null || column.trim().isEmpty()) throw new BusinessException("列名不能为空");
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

        // 治理闭环接点③：分类确认后回写表级敏感标签(自愈：按表是否仍有敏感列增/删"含敏感字段"标签)。
        // 兜底不抛，保证分类主流程不因标签回写失败而回滚。
        try {
            syncTableSensitiveTag(tableMetaId);
        } catch (Exception e) {
            log.warn("表级敏感标签回写失败(不影响分类) tableMetaId={}: {}", tableMetaId, e.getMessage());
        }
    }

    /** 敏感资产盘点(R4)：列出带"含敏感字段"标签的表 + 各表敏感列数，供治理资产盘点。 */
    public List<Map<String, Object>> sensitiveTables() {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
        qw.like("tags", SENSITIVE_TAG).orderByDesc("updated_at");
        List<DnTableMeta> tables = tableMetaMapper.selectList(qw);
        if (tables == null || tables.isEmpty()) return new ArrayList<>();
        // 一次性按表分组统计敏感列数，消除循环内逐表 selectCount(N+1)
        List<Long> tids = tables.stream()
                .map(DnTableMeta::getId).filter(Objects::nonNull).collect(Collectors.toList());
        Map<Long, Long> sensCntMap = countSensitiveColumnsByTable(tids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DnTableMeta t : tables) {
            long sensCnt = (t.getId() == null) ? 0L : sensCntMap.getOrDefault(t.getId(), 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("databaseName", t.getDatabaseName());
            m.put("tableName", t.getTableName());
            m.put("sensitiveColumns", sensCnt);
            m.put("tags", t.getTags());
            m.put("owner", t.getOwner());
            m.put("lastCollectedAt", t.getLastCollectedAt());
            out.add(m);
        }
        return out;
    }

    /** 按 tableMetaId 批量统计敏感列数(sensitive_type 非空)，一条分组查询替代逐表 selectCount。 */
    private Map<Long, Long> countSensitiveColumnsByTable(List<Long> tableMetaIds) {
        if (tableMetaIds == null || tableMetaIds.isEmpty()) return Collections.emptyMap();
        QueryWrapper<DnColumnMeta> cq = new QueryWrapper<>();
        cq.select("table_meta_id AS tid", "COUNT(*) AS cnt")
                .in("table_meta_id", tableMetaIds)
                .isNotNull("sensitive_type").ne("sensitive_type", "")
                .groupBy("table_meta_id");
        List<Map<String, Object>> rows = columnMetaMapper.selectMaps(cq);
        Map<Long, Long> map = new HashMap<>();
        if (rows == null) return map;
        for (Map<String, Object> r : rows) {
            if (r == null) continue;
            Object tid = r.get("tid");
            Object cnt = r.get("cnt");
            if (tid instanceof Number && cnt instanceof Number) {
                map.put(((Number) tid).longValue(), ((Number) cnt).longValue());
            }
        }
        return map;
    }

    /** 按表当前是否仍有敏感列(sensitive_type 非空)，幂等增/删 dn_table_meta 的"含敏感字段"标签。 */
    private void syncTableSensitiveTag(Long tableMetaId) {
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tableMetaId).isNotNull("sensitive_type").ne("sensitive_type", "");
        boolean hasSensitive = columnMetaMapper.selectCount(qw) > 0;
        DnTableMeta tm = tableMetaMapper.selectById(tableMetaId);
        if (tm == null) return;
        String newTags = applySensitiveTag(tm.getTags(), hasSensitive);
        if (!newTags.equals(tm.getTags() == null ? "" : tm.getTags())) {
            tm.setTags(newTags);
            tm.setUpdatedAt(LocalDateTime.now());
            tableMetaMapper.updateById(tm);
        }
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
        try {
            tableMetaMapper.insert(meta);
            return meta.getId();
        } catch (Exception e) {
            // 并发下另一线程可能已先插入(唯一键冲突)，回查复用已存在记录，避免重复表元数据
            Long existed = findTableMetaId(db, table);
            if (existed != null) return existed;
            throw new BusinessException("创建表元数据失败: " + db + "." + table, e);
        }
    }
}
