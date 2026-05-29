package com.datanote.sync.schema;

import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.util.FieldMappingResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自动建表的列处理公共逻辑：按字段映射裁剪/重命名 + 追加同步时间戳列。
 * 供 {@code SyncJobExecutor}（全量/增量）与 {@code CdcEngineManager}（CDC）复用，避免两处实现漂移。
 */
public final class CreateColumnSupport {

    private CreateColumnSupport() {
    }

    /**
     * 按字段映射裁剪列：tc.fields 为空 → 返回原列（全列建表）；非空 → 只保留 sync==true 的列，
     * 列名改为 target（建表用目标列名，保留源类型/可空/主键标记/注释），与引擎写入列保持一致。
     */
    public static List<ColumnDef> applyFieldMapping(List<ColumnDef> cols, TableSyncConfig tc) {
        if (tc.getFields() == null || tc.getFields().isEmpty()) {
            return cols;
        }
        Map<String, String> srcToTgt = FieldMappingResolver.buildSrcToTgt(tc.getFields());
        List<ColumnDef> result = new ArrayList<>();
        for (ColumnDef c : cols) {
            String targetName = srcToTgt.get(c.getName());
            if (targetName == null) {
                continue; // 未选中该列，建表时跳过
            }
            ColumnDef mapped = new ColumnDef();
            mapped.setName(targetName);
            mapped.setColumnType(c.getColumnType());
            mapped.setNullable(c.isNullable());
            mapped.setPrimaryKey(c.isPrimaryKey());
            mapped.setComment(c.getComment());
            result.add(mapped);
        }
        return result;
    }

    /**
     * 若 markSyncTs==1 且 syncTsField 非空且不在现有列中，则在建表列末尾追加一列同步时间戳
     * （DATETIME，可空，非主键），与引擎写入的附加列一致。返回新列表，不改入参。
     */
    public static List<ColumnDef> appendSyncTsColumn(List<ColumnDef> cols, Integer markSyncTs, String syncTsField) {
        if (markSyncTs == null || markSyncTs != 1 || syncTsField == null || syncTsField.trim().isEmpty()) {
            return cols;
        }
        for (ColumnDef c : cols) {
            if (syncTsField.equals(c.getName())) {
                return cols; // 已有同名列，不重复追加
            }
        }
        List<ColumnDef> result = new ArrayList<>(cols);
        ColumnDef ts = new ColumnDef();
        ts.setName(syncTsField);
        ts.setColumnType("DATETIME");
        ts.setNullable(true);
        ts.setPrimaryKey(false);
        ts.setComment("同步时间戳");
        result.add(ts);
        return result;
    }
}
