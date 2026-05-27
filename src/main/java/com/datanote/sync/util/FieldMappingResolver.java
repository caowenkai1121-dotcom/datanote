package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        public Resolved(List<String> srcColumns, List<String> tgtColumns, String pkTarget) {
            this.srcColumns = srcColumns;
            this.tgtColumns = tgtColumns;
            this.pkTarget = pkTarget;
        }
    }

    /**
     * 解析单表字段映射。
     *
     * @param tc          表配置（取 fields）
     * @param allColumns  源表全部列（按序，用于 fields 为空时的全列回退）
     * @param pkSource    源表单列主键名
     * @return 解析结果；fields 为空时 src=tgt=allColumns、pkTarget=pkSource
     * @throws IllegalStateException fields 非空但选中列为空、或主键的 source 未在选中列中
     */
    public static Resolved resolve(TableSyncConfig tc, List<String> allColumns, String pkSource) {
        List<FieldMapping> fields = tc == null ? null : tc.getFields();
        if (fields == null || fields.isEmpty()) {
            return new Resolved(new ArrayList<>(allColumns), new ArrayList<>(allColumns), pkSource);
        }
        Map<String, String> srcToTgt = buildSrcToTgt(fields);
        if (srcToTgt.isEmpty()) {
            throw new IllegalStateException("字段映射已配置但未选中任何同步字段，表: "
                    + (tc.getSourceTable() == null ? "?" : tc.getSourceTable()));
        }
        if (!srcToTgt.containsKey(pkSource)) {
            throw new IllegalStateException("字段映射必须包含主键列 " + pkSource
                    + "（表: " + (tc.getSourceTable() == null ? "?" : tc.getSourceTable()) + "）");
        }
        List<String> srcColumns = new ArrayList<>(srcToTgt.keySet());
        List<String> tgtColumns = new ArrayList<>();
        for (String src : srcColumns) {
            tgtColumns.add(srcToTgt.get(src));
        }
        return new Resolved(srcColumns, tgtColumns, srcToTgt.get(pkSource));
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
