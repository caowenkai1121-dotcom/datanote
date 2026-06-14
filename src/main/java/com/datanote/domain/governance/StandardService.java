package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.governance.mapper.DnDataElementMapper;
import com.datanote.domain.governance.mapper.DnStandardCheckRunMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.governance.mapper.DnWordRootMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.governance.model.DnDataElement;
import com.datanote.domain.governance.model.DnStandardCheckRun;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.domain.governance.model.DnWordRoot;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据标准服务 —— 落标稽核：遍历物理元数据 dn_column_meta，按命名/类型规则比对标准，出落标率。
 * 核心判定逻辑抽为纯静态函数，便于单测；只读 dn_column_meta，不改其实体。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardService {

    private final DnColumnMetaMapper columnMetaMapper;
    private final DnDataElementMapper dataElementMapper;
    private final DnWordRootMapper wordRootMapper;
    private final DnStandardCheckRunMapper checkRunMapper;
    private final DnTableMetaMapper tableMetaMapper;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 不合规清单写库上限，避免 detail 过大 */
    private static final int MAX_DETAIL = 500;

    // ========== 纯函数（可单测） ==========

    /** 列名按下划线拆词：去空、转小写。null/空白返回空列表。 */
    static List<String> splitColumnName(String name) {
        List<String> words = new ArrayList<>();
        if (name == null) {
            return words;
        }
        for (String part : name.trim().toLowerCase().split("_")) {
            if (!part.isEmpty()) {
                words.add(part);
            }
        }
        return words;
    }

    /** 命名合规：拆词后每个词都命中词根集合才合规；空列名/无词视为不合规。 */
    static boolean isNamingCompliant(String columnName, Set<String> roots) {
        List<String> words = splitColumnName(columnName);
        if (words.isEmpty()) {
            return false;
        }
        for (String w : words) {
            if (!roots.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** 返回不命中词根集合的词（用于清单提示），保持原拆词顺序。 */
    static List<String> nonCompliantWords(String columnName, Set<String> roots) {
        List<String> miss = new ArrayList<>();
        for (String w : splitColumnName(columnName)) {
            if (!roots.contains(w)) {
                miss.add(w);
            }
        }
        return miss;
    }

    /**
     * 类型比对：提取主类型名（忽略长度/修饰），忽略大小写。
     * 标准类型为空 -> 不校验视为匹配；物理类型为空 -> 无法比对视为匹配（不误报）。
     */
    static boolean typeMatches(String physicalType, String standardType) {
        if (standardType == null || standardType.trim().isEmpty()) {
            return true;
        }
        if (physicalType == null || physicalType.trim().isEmpty()) {
            return true;
        }
        return baseType(physicalType).equals(baseType(standardType));
    }

    /** 取类型主名：小写、去括号内长度、取首个 token（如 "int unsigned" -> "int"）。 */
    private static String baseType(String type) {
        String t = type.trim().toLowerCase();
        int p = t.indexOf('(');
        if (p >= 0) {
            t = t.substring(0, p);
        }
        t = t.trim();
        int sp = t.indexOf(' ');
        if (sp >= 0) {
            t = t.substring(0, sp);
        }
        return t.trim();
    }

    // ========== 落标稽核 ==========

    /** 执行落标稽核：遍历 dn_column_meta，比对命名与类型，写结果表并返回。 */
    @Transactional(rollbackFor = Exception.class)
    public DnStandardCheckRun runCheck(String scope) {
        Set<String> roots = loadRoots();
        Map<String, String> elementTypes = loadElementTypes();
        Set<Long> scopeIds = resolveScopeTableIds(scope);   // null=全量, 否则限定这些表的列

        List<DnColumnMeta> columns = columnMetaMapper.selectList(null);
        if (columns == null) {
            columns = Collections.emptyList();
        }
        int total = 0;
        int violation = 0;
        List<Map<String, Object>> detail = new ArrayList<>();

        for (DnColumnMeta col : columns) {
            if (col == null) {
                continue;
            }
            // 范围过滤: 选了 db / db.table 时只稽核该范围内的列(原 scope 参数仅入库未生效)
            if (scopeIds != null && (col.getTableMetaId() == null || !scopeIds.contains(col.getTableMetaId()))) {
                continue;
            }
            total++;
            String colName = col.getColumnName();
            boolean bad = false;
            StringBuilder reason = new StringBuilder();

            // 规则一：命名规范（每个拆词应命中词根）
            if (!isNamingCompliant(colName, roots)) {
                bad = true;
                List<String> miss = nonCompliantWords(colName, roots);
                reason.append("命名未命中词根: ").append(miss.isEmpty() ? "(空列名)" : String.join(",", miss));
            }

            // 规则二：列名等于某数据元 element_code 时比对类型
            String key = colName == null ? "" : colName.trim().toLowerCase();
            if (elementTypes.containsKey(key)) {
                String stdType = elementTypes.get(key);
                if (!typeMatches(col.getDataType(), stdType)) {
                    bad = true;
                    if (reason.length() > 0) {
                        reason.append("; ");
                    }
                    reason.append("类型不符标准: 实际 ").append(col.getDataType())
                            .append(" 标准 ").append(stdType);
                }
            }

            if (bad) {
                violation++;
                if (detail.size() < MAX_DETAIL) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("tableMetaId", col.getTableMetaId());
                    item.put("columnName", colName);
                    item.put("dataType", col.getDataType());
                    item.put("reason", reason.toString());
                    detail.add(item);
                }
            }
        }

        BigDecimal passRate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(total - violation)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        DnStandardCheckRun run = new DnStandardCheckRun();
        run.setScope(scope == null || scope.trim().isEmpty() ? "all" : scope.trim());
        run.setTotalCount(total);
        run.setViolationCount(violation);
        run.setPassRate(passRate);
        run.setDetail(toJson(detail));
        run.setCreatedAt(LocalDateTime.now());
        checkRunMapper.insert(run);
        return run;
    }

    /** 解析稽核范围 scope("db" 或 "db.table" 或空)为允许的 table_meta id 集合; 空范围返回 null 表示全量。 */
    private Set<Long> resolveScopeTableIds(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return null;
        }
        String s = scope.trim();
        String db, table = null;
        int dot = s.indexOf('.');
        if (dot >= 0) {
            db = s.substring(0, dot);
            table = s.substring(dot + 1);
        } else {
            db = s;
        }
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
        qw.eq("database_name", db);
        if (table != null && !table.trim().isEmpty()) {
            qw.eq("table_name", table.trim());
        }
        qw.select("id");
        List<DnTableMeta> tbls = tableMetaMapper.selectList(qw);
        Set<Long> ids = new HashSet<>();
        if (tbls != null) {
            for (DnTableMeta t : tbls) {
                if (t != null && t.getId() != null) {
                    ids.add(t.getId());
                }
            }
        }
        return ids;
    }

    /** 加载词根集合：word_en 与 abbr 合并、小写、去空。 */
    private Set<String> loadRoots() {
        Set<String> roots = new HashSet<>();
        List<DnWordRoot> list = wordRootMapper.selectList(null);
        if (list == null) {
            return roots;
        }
        for (DnWordRoot r : list) {
            if (r == null) {
                continue;
            }
            addRoot(roots, r.getWordEn());
            addRoot(roots, r.getAbbr());
        }
        return roots;
    }

    private void addRoot(Set<String> roots, String w) {
        if (w != null && !w.trim().isEmpty()) {
            roots.add(w.trim().toLowerCase());
        }
    }

    /** 加载数据元类型映射：element_code(小写) -> data_type。 */
    private Map<String, String> loadElementTypes() {
        Map<String, String> map = new HashMap<>();
        List<DnDataElement> list = dataElementMapper.selectList(null);
        if (list == null) {
            return map;
        }
        for (DnDataElement e : list) {
            if (e != null && e.getElementCode() != null && !e.getElementCode().trim().isEmpty()) {
                map.put(e.getElementCode().trim().toLowerCase(), e.getDataType());
            }
        }
        return map;
    }

    private String toJson(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("稽核明细序列化失败", e);
            return "[]";
        }
    }

    /** 规范违规 Top：解析最近一次稽核明细，按表聚合违规列数降序 Top N。 */
    public List<Map<String, Object>> topViolations(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int topN = Math.max(1, Math.min(limit, 50)); // 守卫：限定 1-50，防越界/负数
        QueryWrapper<DnStandardCheckRun> qw = new QueryWrapper<>();
        qw.orderByDesc("created_at").last("LIMIT 1");
        DnStandardCheckRun latest = checkRunMapper.selectOne(qw);
        if (latest == null || latest.getDetail() == null) return out;
        try {
            List<Map<String, Object>> items = JSON.readValue(latest.getDetail(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            if (items == null) {
                return out;
            }
            Map<Long, Integer> byTable = new LinkedHashMap<>();
            for (Map<String, Object> it : items) {
                if (it == null) continue;
                Object tid = it.get("tableMetaId");
                if (!(tid instanceof Number)) continue; // 非数值(含 null)跳过，避免 ClassCastException
                byTable.merge(((Number) tid).longValue(), 1, Integer::sum);
            }
            List<Map.Entry<Long, Integer>> entries = new ArrayList<>(byTable.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // 避免 int 相减溢出
            // 只取 Top N 的表 id，一次性批量查元数据，消除循环内 selectById(N+1)
            List<Long> topIds = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : entries) {
                if (topIds.size() >= topN) break;
                topIds.add(e.getKey());
            }
            Map<Long, DnTableMeta> tableMap = new HashMap<>();
            if (!topIds.isEmpty()) {
                List<DnTableMeta> tables = tableMetaMapper.selectBatchIds(topIds);
                if (tables != null) {
                    for (DnTableMeta tm : tables) {
                        if (tm != null && tm.getId() != null) {
                            tableMap.put(tm.getId(), tm);
                        }
                    }
                }
            }
            for (Long tid : topIds) {
                DnTableMeta tm = tableMap.get(tid);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("db", tm != null ? tm.getDatabaseName() : "?");
                m.put("table", tm != null ? tm.getTableName() : ("#" + tid));
                m.put("violations", byTable.get(tid));
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("解析稽核明细失败: latestRunId={}", latest.getId(), ex);
        }
        return out;
    }

    /** 稽核历史最近 N 条（不含 detail，列表轻量） */
    public List<DnStandardCheckRun> recentRuns(int limit) {
        QueryWrapper<DnStandardCheckRun> qw = new QueryWrapper<>();
        qw.select("id", "scope", "total_count", "violation_count", "pass_rate", "created_at")
                .orderByDesc("created_at").last("LIMIT " + Math.max(1, Math.min(limit, 100)));
        List<DnStandardCheckRun> list = checkRunMapper.selectList(qw);
        return list == null ? Collections.emptyList() : list;
    }
}
