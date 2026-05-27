package com.datanote.sync.engine;

import com.datanote.sync.dto.SyncContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步时间戳列（迭代V3）公共逻辑：全量/增量引擎写入时在写列末尾追加一列 syncTsField，
 * 绑定该列为当前时间。读列（源列）不含该列。
 *
 * <p>仅当 {@code markSyncTs==1} 且 {@code syncTsField} 非空、且该列尚未出现在写列中时追加，
 * 避免与字段映射产生的同名目标列重复。
 */
public final class SyncTsSupport {

    private SyncTsSupport() {
    }

    /**
     * 是否需要追加同步时间戳列。
     * @param ctx        同步上下文（取 markSyncTs / syncTsField）
     * @param tgtColumns 字段映射后的写列（目标列名）
     */
    public static boolean shouldAppend(SyncContext ctx, List<String> tgtColumns) {
        Integer mark = ctx.getMarkSyncTs();
        String field = ctx.getSyncTsField();
        if (mark == null || mark != 1 || field == null || field.trim().isEmpty()) {
            return false;
        }
        return tgtColumns == null || !tgtColumns.contains(field);
    }

    /**
     * 写列 = tgtColumns（append 为 true 时末尾追加 syncTsField）。返回新列表，不改入参。
     */
    public static List<String> appendTsColumn(List<String> tgtColumns, SyncContext ctx, boolean append) {
        List<String> result = new ArrayList<>(tgtColumns);
        if (append) {
            result.add(ctx.getSyncTsField());
        }
        return result;
    }
}
