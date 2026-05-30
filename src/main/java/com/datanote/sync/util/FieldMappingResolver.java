package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段映射解析：把 TableSyncConfig.fields 解析为读列(source)/写列(target)序列及主键映射。
 *
 * <p>约定：fields 为 null/空 → 同步全部列（向后兼容，不裁剪不重命名）；
 * 非空 → 只保留 sync==true 的字段，读列名取 source、写列名取 target（target 空则回退 source），
 * 二者按同一顺序一一对应。读/写按各自顺序绑定即可。
 *
 * <p>keyset 分页与 UPSERT 依赖单列主键，故要求主键的 source 必须在选中列中。
 */
public final class FieldMappingResolver {

    private FieldMappingResolver() {
    }

    /** 解析结果：读列(源名)、写列(目标名，与读列一一对应)、主键的目标名。 */
    public static final class Resolved {
        public final List<String> srcColumns;
        public final List<String> tgtColumns;
        public final String pkTarget;
        /** M1：源列名 -> FieldMapping，仅含 sync==true 且 source 非空的字段；fields 为空时为空 Map。 */
        public final Map<String, FieldMapping> srcToFieldMapping;
        /** M2b：主键源列名列表（无主键时为空 list）。 */
        public final List<String> pkSourceColumns;
        /** M2b：主键目标列名列表（无主键时为空 list，与 pkSourceColumns 一一对应）。 */
        public final List<String> pkTargetColumns;

        /** 向后兼容的 3 参构造器（srcToFieldMapping 默认为空 Map）。 */
        public Resolved(List<String> srcColumns, List<String> tgtColumns, String pkTarget) {
            this(srcColumns, tgtColumns, pkTarget, Collections.emptyMap());
        }

        public Resolved(List<String> srcColumns, List<String> tgtColumns, String pkTarget,
                        Map<String, FieldMapping> srcToFieldMapping) {
            this(srcColumns, tgtColumns, pkTarget, srcToFieldMapping,
                    pkTarget != null ? Collections.singletonList(pkTarget) : Collections.emptyList(),
                    pkTarget != null ? Collections.singletonList(pkTarget) : Collections.emptyList());
        }

        public Resolved(List<String> srcColumns, List<String> tgtColumns, String pkTarget,
                        Map<String, FieldMapping> srcToFieldMapping,
                        List<String> pkSourceColumns, List<String> pkTargetColumns) {
            this.srcColumns = srcColumns;
            this.tgtColumns = tgtColumns;
            this.pkTarget = pkTarget;
            this.srcToFieldMapping = srcToFieldMapping;
            this.pkSourceColumns = pkSourceColumns != null ? pkSourceColumns : Collections.emptyList();
            this.pkTargetColumns = pkTargetColumns != null ? pkTargetColumns : Collections.emptyList();
        }
    }

    /**
     * 解析单表字段映射（单主键，向后兼容入口）。
     *
     * @param tc          表配置（取 fields）
     * @param allColumns  源表全部列（按序，用于 fields 为空时的全列回退）
     * @param pkSource    源表单列主键名（null 视为无主键）
     * @return 解析结果；fields 为空时 src=tgt=allColumns、pkTarget=pkSource
     */
    public static Resolved resolve(TableSyncConfig tc, List<String> allColumns, String pkSource) {
        return resolve(tc, allColumns,
                pkSource == null ? Collections.emptyList() : Collections.singletonList(pkSource));
    }

    /**
     * 解析单表字段映射（复合/无主键重载）。
     *
     * @param tc          表配置（取 fields）
     * @param allColumns  源表全部列（按序，用于 fields 为空时的全列回退）
     * @param pkSources   源表主键列名列表（空 list 表示无主键，跳过主键校验）
     * @return 解析结果
     * @throws IllegalStateException fields 非空但选中列为空、或主键的 source 未在选中列中
     */
    public static Resolved resolve(TableSyncConfig tc, List<String> allColumns, List<String> pkSources) {
        List<String> pks = pkSources == null ? Collections.emptyList() : pkSources;
        List<FieldMapping> fields = tc == null ? null : tc.getFields();
        if (fields == null || fields.isEmpty()) {
            // 全列回退：source = target
            List<String> pkTgtCols = new ArrayList<>();
            for (String ps : pks) pkTgtCols.add(ps);
            String pkTarget = pkTgtCols.isEmpty() ? null : pkTgtCols.get(0);
            return new Resolved(new ArrayList<>(allColumns), new ArrayList<>(allColumns), pkTarget,
                    Collections.emptyMap(), new ArrayList<>(pks), pkTgtCols);
        }
        Map<String, String> srcToTgt = buildSrcToTgt(fields);
        if (srcToTgt.isEmpty()) {
            throw new IllegalStateException("字段映射已配置但未选中任何同步字段，表: "
                    + (tc.getSourceTable() == null ? "?" : tc.getSourceTable()));
        }
        // 主键非空时校验每个主键源列均在选中列中
        if (!pks.isEmpty()) {
            for (String pkSrc : pks) {
                if (!srcToTgt.containsKey(pkSrc)) {
                    throw new IllegalStateException("字段映射必须包含主键列 " + pkSrc
                            + "（表: " + (tc.getSourceTable() == null ? "?" : tc.getSourceTable()) + "）");
                }
            }
        }
        // 不允许多个源列映射到同一目标列
        Set<String> seenTgt = new HashSet<>();
        for (String t : srcToTgt.values()) {
            if (!seenTgt.add(t.toLowerCase())) {
                throw new IllegalStateException("字段映射存在重复的目标列 " + t
                        + "（表: " + (tc.getSourceTable() == null ? "?" : tc.getSourceTable()) + "）");
            }
        }
        List<String> srcColumns = new ArrayList<>(srcToTgt.keySet());
        List<String> tgtColumns = new ArrayList<>();
        for (String src : srcColumns) {
            tgtColumns.add(srcToTgt.get(src));
        }
        // 构建 source -> FieldMapping 映射（仅 sync==true 且 source 非空）
        Map<String, FieldMapping> src2fm = new LinkedHashMap<>();
        for (FieldMapping fm : fields) {
            if (fm == null || !Boolean.TRUE.equals(fm.getSync())) continue;
            String src = fm.getSource();
            if (src == null || src.trim().isEmpty()) continue;
            src2fm.put(src, fm);
        }
        // 构建主键目标列名列表
        List<String> pkSrcCols = new ArrayList<>(pks);
        List<String> pkTgtCols = new ArrayList<>();
        for (String ps : pks) pkTgtCols.add(srcToTgt.get(ps));
        String pkTarget = pkTgtCols.isEmpty() ? null : pkTgtCols.get(0);
        return new Resolved(srcColumns, tgtColumns, pkTarget, src2fm, pkSrcCols, pkTgtCols);
    }

    /**
     * 构造 source->target 映射（仅 sync==true、source 非空；target 空则回退 source）。
     * 保持配置顺序（LinkedHashMap），同名 source 后者覆盖。
     */
    public static Map<String, String> buildSrcToTgt(List<FieldMapping> fields) {
        Map<String, String> map = new LinkedHashMap<>();
        if (fields == null) {
            return map;
        }
        for (FieldMapping fm : fields) {
            if (fm == null || !Boolean.TRUE.equals(fm.getSync())) {
                continue;
            }
            String src = fm.getSource();
            if (src == null || src.trim().isEmpty()) {
                continue;
            }
            String tgt = fm.getTarget() == null || fm.getTarget().trim().isEmpty() ? src : fm.getTarget();
            map.put(src, tgt);
        }
        return map;
    }
}
