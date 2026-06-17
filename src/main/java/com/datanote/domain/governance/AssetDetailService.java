package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.common.exception.BusinessException;
import com.datanote.platform.config.HiveConfig;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.governance.mapper.DnGlossaryTermMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.governance.model.DnGlossaryTerm;
import com.datanote.domain.metadata.model.DnTableMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 资产详情 Service — 字段级元数据聚合 / Profiler 下推探查 / 业务术语 CRUD
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetDetailService {

    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnGlossaryTermMapper glossaryTermMapper;
    private final HiveConfig hiveConfig;
    private final com.datanote.domain.integration.connector.ConnectionManager connectionManager; // 源库取数(看真实表数据)
    private final com.datanote.platform.ai.vector.SemanticSearchService semanticSearchService;   // 相似资产推荐(向量召回)

    /** Profiler 单次探查的最大字段数，防止数仓聚合查询过慢 */
    static final int MAX_PROFILE_FIELDS = 30;

    /** 合法标识符：字母/数字/下划线/中文，1-128 字符（库名/表名/列名拼入原生 SQL 前必校验，防注入） */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[\\w\\u4e00-\\u9fa5]{1,128}$");

    private static boolean validIdentifier(String name) {
        return name != null && IDENTIFIER_PATTERN.matcher(name).matches();
    }

    private static void requireIdentifier(String name, String label) {
        if (!validIdentifier(name)) {
            throw new BusinessException(label + " 包含非法字符: " + name);
        }
    }

    // ========== 资产详情：表 + 字段级元数据 ==========

    public Map<String, Object> assetDetail(String db, String table) {
        DnTableMeta meta = findTableMeta(db, table);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("table", meta);
        List<DnColumnMeta> columns = new ArrayList<DnColumnMeta>();
        if (meta != null) {
            QueryWrapper<DnColumnMeta> qw = new QueryWrapper<DnColumnMeta>();
            qw.eq("table_meta_id", meta.getId()).orderByAsc("ordinal").orderByAsc("id");
            columns = columnMetaMapper.selectList(qw);
            if (columns == null) columns = new ArrayList<DnColumnMeta>();
        }
        result.put("columns", columns);
        result.put("similarTables", similarTables(db, table, meta));   // 相似资产推荐(向量召回, 排除自身)
        return result;
    }

    /** 向量召回与本表语义相近的其它表(排除自身), 最多 6 张; 向量不可用返空。 */
    private List<Map<String, Object>> similarTables(String db, String table, DnTableMeta meta) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try {
            String q = (meta != null && meta.getTableComment() != null && !meta.getTableComment().trim().isEmpty())
                    ? meta.getTableComment() : table;
            Map<String, Object> rag = semanticSearchService.search(q, "table", 8);
            if (rag == null || !"vector".equals(rag.get("engine")) || !(rag.get("results") instanceof List)) return out;
            String self = (db + "." + table).toLowerCase();
            for (Object o : (List<?>) rag.get("results")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> h = (Map<?, ?>) o;
                Object hdb = h.get("db"), hname = h.get("name");
                if (hdb == null || hname == null) continue;
                if ((hdb + "." + hname).toLowerCase().equals(self)) continue;   // 排除自身
                Map<String, Object> m = new HashMap<String, Object>();
                m.put("db", hdb);
                m.put("table", hname);
                m.put("title", h.get("title"));
                m.put("score", h.get("score"));
                out.add(m);
                if (out.size() >= 6) break;
            }
        } catch (Exception e) {
            log.warn("相似资产召回失败: {}", e.getMessage());
        }
        return out;
    }

    private DnTableMeta findTableMeta(String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<DnTableMeta>();
        qw.eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        return tableMetaMapper.selectOne(qw);
    }

    // ========== Profiler 探查：下推数仓采样 ==========

    public Map<String, Object> profile(String db, String table) throws SQLException {
        requireIdentifier(db, "数据库名");   // db/table 拼入原生 COUNT/聚合 SQL,先校验防注入
        requireIdentifier(table, "表名");
        List<String> columnNames = listColumnNames(db, table);
        long totalRows = 0;
        int max = limitFields(columnNames.size(), MAX_PROFILE_FIELDS);
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        try (Connection conn = hiveConfig.getConnection();   // COUNT(*) 与逐字段探查复用同一连接,省连接开销
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM `" + db + "`.`" + table + "`")) {
                if (rs.next()) totalRows = rs.getLong(1);
            }
            for (int i = 0; i < max; i++) {
                String name = columnNames.get(i);
                Map<String, Object> stat = new HashMap<String, Object>();
                stat.put("name", name);
                if (!validIdentifier(name)) {
                    stat.put("error", "非法列名,跳过探查");   // 列名拼入聚合 SQL,非法则跳过防注入
                    fields.add(stat);
                    continue;
                }
                try {
                    String col = "`" + name + "`";
                    String sql = "SELECT SUM(CASE WHEN " + col + " IS NULL THEN 1 ELSE 0 END) AS null_count, "
                            + "COUNT(DISTINCT " + col + ") AS distinct_count "
                            + "FROM `" + db + "`.`" + table + "`";
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) {
                            long nullCount = rs.getLong("null_count");
                            stat.put("nullCount", nullCount);
                            stat.put("nullRate", formatRate(nullCount, totalRows));
                            stat.put("distinctCount", rs.getLong("distinct_count"));
                        }
                    }
                } catch (SQLException e) {
                    stat.put("error", e.getMessage());
                }
                fields.add(stat);
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("totalRows", totalRows);
        result.put("columnCount", columnNames.size());
        result.put("profiledCount", max);
        result.put("fields", fields);
        return result;
    }

    /**
     * 取一张表的样例数据行(SELECT * LIMIT n); 供会话内直接查看表数据。limit 默认20上限50, 单元格超长截断。
     * 数据源路由: MySQL 源库(有 datasourceId 且 dbType=MYSQL)连【源库】取真实数据; 否则连【数仓】(Doris)。
     */
    public Map<String, Object> sampleRows(String db, String table, int limit) throws SQLException {
        return read(db, table, limit <= 0 ? 20 : Math.min(limit, 50));
    }

    /** 批量读源表行(供 MDM 源表导入); limit 默认200上限2000。与 sampleRows 同路由(MySQL源库/数仓)。 */
    public Map<String, Object> readRows(String db, String table, int limit) throws SQLException {
        return read(db, table, limit <= 0 ? 200 : Math.min(limit, 2000));
    }

    private Map<String, Object> read(String db, String table, int n) throws SQLException {
        requireIdentifier(db, "数据库名");   // db/table 拼入原生 SQL,先校验防注入
        requireIdentifier(table, "表名");
        DnTableMeta meta = findTableMeta(db, table);
        boolean useSource = meta != null && meta.getDatasourceId() != null
                && meta.getDbType() != null && meta.getDbType().toUpperCase().contains("MYSQL");
        List<String> columns = new ArrayList<String>();
        List<List<Object>> rows = new ArrayList<List<Object>>();
        Connection conn = null;
        try {
            conn = useSource ? connectionManager.getConnection(meta.getDatasourceId(), db) : hiveConfig.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM `" + db + "`.`" + table + "` LIMIT " + n)) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int cc = md.getColumnCount();
                for (int i = 1; i <= cc; i++) columns.add(md.getColumnLabel(i));
                while (rs.next()) {
                    List<Object> row = new ArrayList<Object>();
                    for (int i = 1; i <= cc; i++) {
                        Object v = rs.getObject(i);
                        String s = v == null ? null : String.valueOf(v);
                        if (s != null && s.length() > 200) s = s.substring(0, 200) + "…"; // 防超长单元格撑爆
                        row.add(s);
                    }
                    rows.add(row);
                }
            }
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignore) {} // 源库为 Hikari 池连接, close 即归还
        }
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("returned", rows.size());
        out.put("limit", n);
        out.put("source", useSource ? "源库" : "数仓");
        return out;
    }

    /** 找相近的【真实】表(供"表未找到"时给候选, 让 agent 据真实清单求证而非臆造): 同表名(可能库写错)→表名模糊→同库其它表。 */
    public List<String> findSimilarTables(String db, String table, int limit) {
        List<String> out = new ArrayList<String>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int lim = Math.max(1, Math.min(limit, 20));
        try {
            if (table != null && !table.isEmpty()) {                 // 1) 同表名(库名可能写错)
                QueryWrapper<DnTableMeta> q = new QueryWrapper<DnTableMeta>();
                q.eq("table_name", table).last("LIMIT " + lim);
                collectNames(tableMetaMapper.selectList(q), out, seen, lim);
            }
            if (out.size() < lim && table != null && table.length() >= 2) { // 2) 表名模糊包含
                QueryWrapper<DnTableMeta> q = new QueryWrapper<DnTableMeta>();
                q.like("table_name", table).last("LIMIT " + lim);
                collectNames(tableMetaMapper.selectList(q), out, seen, lim);
            }
            if (out.size() < lim && db != null && !db.isEmpty()) {   // 3) 同库其它表
                QueryWrapper<DnTableMeta> q = new QueryWrapper<DnTableMeta>();
                q.eq("database_name", db).last("LIMIT " + lim);
                collectNames(tableMetaMapper.selectList(q), out, seen, lim);
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** 按 库.表 解析其所属数据源ID(供建表/同步等【自动补全】datasourceId, 免去反问用户); 精确表优先, 退化为同库任一表。 */
    public Long resolveDatasourceId(String db, String table) {
        try {
            if (db != null && table != null) {
                DnTableMeta m = findTableMeta(db, table);
                if (m != null && m.getDatasourceId() != null) return m.getDatasourceId();
            }
            if (db != null && !db.isEmpty()) {
                DnTableMeta m = tableMetaMapper.selectOne(new QueryWrapper<DnTableMeta>()
                        .eq("database_name", db).isNotNull("datasource_id").last("LIMIT 1"));
                if (m != null) return m.getDatasourceId();
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static void collectNames(List<DnTableMeta> ms, List<String> out, java.util.Set<String> seen, int lim) {
        if (ms == null) return;
        for (DnTableMeta m : ms) {
            if (out.size() >= lim) break;
            String k = m.getDatabaseName() + "." + m.getTableName();
            if (seen.add(k)) out.add(k);
        }
    }

    /** 优先取已采集的字段元数据列名，缺失则降级 information_schema */
    private List<String> listColumnNames(String db, String table) throws SQLException {
        DnTableMeta meta = findTableMeta(db, table);
        if (meta != null) {
            QueryWrapper<DnColumnMeta> qw = new QueryWrapper<DnColumnMeta>();
            qw.eq("table_meta_id", meta.getId()).orderByAsc("ordinal").orderByAsc("id");
            List<DnColumnMeta> cols = columnMetaMapper.selectList(qw);
            if (cols != null && !cols.isEmpty()) {
                List<String> names = new ArrayList<String>();
                for (DnColumnMeta c : cols) names.add(c.getColumnName());
                return names;
            }
        }
        List<String> names = new ArrayList<String>();
        try (Connection conn = hiveConfig.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    // ========== 业务术语 CRUD ==========

    public List<DnGlossaryTerm> listTerms() {
        QueryWrapper<DnGlossaryTerm> qw = new QueryWrapper<DnGlossaryTerm>();
        qw.orderByDesc("created_at").last("LIMIT 200");
        return glossaryTermMapper.selectList(qw);
    }

    public DnGlossaryTerm saveTerm(DnGlossaryTerm term) {
        if (term == null) throw new BusinessException("术语内容不能为空");
        if (term.getId() != null) {
            glossaryTermMapper.updateById(term);
        } else {
            term.setCreatedAt(LocalDateTime.now());
            glossaryTermMapper.insert(term);
        }
        return term;
    }

    public void deleteTerm(Long id) {
        glossaryTermMapper.deleteById(id);
    }

    // ========== 纯函数（便于单测） ==========

    /** 空值率格式化，total<=0 返回 "0%"，否则保留一位百分比 */
    static String formatRate(long nullCount, long total) {
        if (total <= 0) return "0%";
        return String.format("%.1f%%", nullCount * 100.0 / total);
    }

    /** Profiler 字段限流：返回不超过 max 的字段数 */
    static int limitFields(int size, int max) {
        return Math.min(Math.max(size, 0), max);
    }
}
