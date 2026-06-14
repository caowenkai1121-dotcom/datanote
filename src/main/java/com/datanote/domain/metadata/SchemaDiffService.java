package com.datanote.domain.metadata;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.DatasourceExploreService;
import com.datanote.domain.metadata.model.ColumnInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表结构对比 — 比较两张表的字段差异(新增/删除/类型变更/一致)。
 * 用于 ODS↔DWD、跨环境、迁移前后等一致性核对。核心 compare 为纯函数, 便于单测。
 */
@Service
@RequiredArgsConstructor
public class SchemaDiffService {

    private final DatasourceExploreService exploreService;

    /** 对比两张表结构; 库/表名空校验, 列从在线元数据取。 */
    public Map<String, Object> diff(String db1, String table1, String db2, String table2) throws SQLException {
        if (isBlank(db1) || isBlank(table1) || isBlank(db2) || isBlank(table2)) {
            throw new BusinessException("两侧库名与表名均不能为空");
        }
        List<ColumnInfo> left = exploreService.getHiveColumns(db1.trim(), table1.trim());
        List<ColumnInfo> right = exploreService.getHiveColumns(db2.trim(), table2.trim());
        Map<String, Object> result = compare(left, right);
        result.put("left", db1.trim() + "." + table1.trim());
        result.put("right", db2.trim() + "." + table2.trim());
        return result;
    }

    /** 纯函数: 以字段名(小写)对齐, 计算 added(右有左无)/removed(左有右无)/changed(类型不同)/sameCount。 */
    static Map<String, Object> compare(List<ColumnInfo> left, List<ColumnInfo> right) {
        Map<String, ColumnInfo> leftMap = byName(left);
        Map<String, ColumnInfo> rightMap = byName(right);

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        int same = 0;

        for (Map.Entry<String, ColumnInfo> e : leftMap.entrySet()) {
            ColumnInfo r = rightMap.get(e.getKey());
            if (r == null) {
                removed.add(col(e.getValue()));
            } else if (!normType(e.getValue().getType()).equals(normType(r.getType()))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", e.getValue().getName());
                c.put("leftType", e.getValue().getType());
                c.put("rightType", r.getType());
                changed.add(c);
            } else {
                same++;
            }
        }
        for (Map.Entry<String, ColumnInfo> e : rightMap.entrySet()) {
            if (!leftMap.containsKey(e.getKey())) added.add(col(e.getValue()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        out.put("sameCount", same);
        out.put("identical", added.isEmpty() && removed.isEmpty() && changed.isEmpty());
        return out;
    }

    private static Map<String, ColumnInfo> byName(List<ColumnInfo> cols) {
        Map<String, ColumnInfo> m = new LinkedHashMap<>();
        if (cols != null) {
            for (ColumnInfo c : cols) {
                if (c != null && c.getName() != null) m.put(c.getName().trim().toLowerCase(), c);
            }
        }
        return m;
    }

    private static Map<String, Object> col(ColumnInfo c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("type", c.getType());
        return m;
    }

    /** 类型归一: 去空白小写, 便于忽略大小写/前后空格的等价比较。 */
    private static String normType(String t) {
        return t == null ? "" : t.trim().toLowerCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
