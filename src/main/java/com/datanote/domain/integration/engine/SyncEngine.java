package com.datanote.domain.integration.engine;

import com.datanote.domain.integration.dto.SyncContext;

/**
 * 同步引擎统一接口。
 */
public interface SyncEngine {
    void sync(SyncContext ctx);

    /**
     * 大表自适应批次（全量/增量引擎共用）：用 information_schema 估行数（即时近似），
     * &gt;1亿用 10000、&gt;1千万用 5000，否则用配置基础批次。减少分页往返与目标端提交次数。
     * 估算失败/未知回退基础批次。复用调用方已开的源连接，不另开连接。
     */
    static int adaptiveBatch(SyncContext ctx, String srcDb, String table, java.sql.Connection srcConn) {
        int base = ctx.getBatchSize();
        long est = -1L;
        try { est = ctx.getSource().estimateRowCount(srcConn, srcDb, table); } catch (Exception e) { /* 回退基础 */ }
        int eff = base;
        if (est >= 100_000_000L) eff = Math.max(base, 10000);
        else if (est >= 10_000_000L) eff = Math.max(base, 5000);
        if (eff != base) ctx.log("INFO", "大表自适应批次: " + table + " 约 " + est + " 行, batchSize " + base + " -> " + eff);
        return eff;
    }
}
