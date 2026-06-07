package com.datanote.domain.governance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    /** Profiler 单次探查的最大字段数，防止数仓聚合查询过慢 */
    static final int MAX_PROFILE_FIELDS = 30;

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
        }
        result.put("columns", columns);
        return result;
    }

    private DnTableMeta findTableMeta(String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<DnTableMeta>();
        qw.eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        return tableMetaMapper.selectOne(qw);
    }

    // ========== Profiler 探查：下推数仓采样 ==========

    public Map<String, Object> profile(String db, String table) throws SQLException {
        List<String> columnNames = listColumnNames(db, table);
        long totalRows = 0;
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM `" + db + "`.`" + table + "`")) {
            if (rs.next()) totalRows = rs.getLong(1);
        }

        int max = limitFields(columnNames.size(), MAX_PROFILE_FIELDS);
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = 0; i < max; i++) {
                String name = columnNames.get(i);
                Map<String, Object> stat = new HashMap<String, Object>();
                stat.put("name", name);
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
