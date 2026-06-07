package com.datanote.domain.integration.util;

import com.datanote.domain.integration.dto.FieldMapping;

import java.util.List;
import java.util.Map;

/**
 * 行值加工管道：逐列 空值处理→转换→脱敏。
 * 空映射=直通。SKIP_ROW 返回 null（跳行）。
 */
public final class RowValueProcessor {

    private final Map<String, FieldMapping> srcToFieldMapping;
    private final boolean active;

    public RowValueProcessor(Map<String, FieldMapping> srcToFieldMapping) {
        this.srcToFieldMapping = srcToFieldMapping;
        this.active = srcToFieldMapping != null && !srcToFieldMapping.isEmpty();
    }

    /**
     * 返回加工后值数组；返回 null 表示此行应跳过。
     * 空管道直接返回原数组。
     */
    public Object[] process(List<String> srcColumns, Object[] raw) {
        if (!active) return raw;
        Object[] out = new Object[raw.length];
        for (int i = 0; i < raw.length; i++) {
            FieldMapping fm = srcToFieldMapping.get(srcColumns.get(i));
            Object v = raw[i];
            if (fm == null) {
                out[i] = v;
                continue;
            }
            v = NullValueHandler.handle(v, fm);
            if (v == NullValueHandler.SKIP) return null;
            v = ValueTransformer.transform(v, fm.getTransformExpression());
            v = PiiMasker.mask(v, fm.getMaskingType(), fm.getMaskingSalt());
            out[i] = v;
        }
        return out;
    }
}
