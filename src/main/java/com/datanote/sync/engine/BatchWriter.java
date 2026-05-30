package com.datanote.sync.engine;

import com.datanote.sync.dto.SyncContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/** 批量写:正常 addBatch/executeBatch;批失败回退逐行定位坏行,按 errorLimit 阈值容错。 */
public final class BatchWriter {
    private final PreparedStatement ps;
    private final Connection conn;
    private final SyncContext ctx;
    private final int writeColCount;
    private final String sourceTable;
    private final List<Object[]> buffer = new ArrayList<>();

    public BatchWriter(PreparedStatement ps, Connection conn, SyncContext ctx, int writeColCount, String sourceTable) {
        this.ps = ps; this.conn = conn; this.ctx = ctx; this.writeColCount = writeColCount; this.sourceTable = sourceTable;
    }

    public void add(Object[] writeRow) throws Exception {
        for (int i = 0; i < writeColCount; i++) ps.setObject(i + 1, writeRow[i]);
        ps.addBatch();
        buffer.add(writeRow);
    }

    public int buffered() { return buffer.size(); }

    public void flush() throws Exception {
        if (buffer.isEmpty()) return;
        try {
            ps.executeBatch();
            conn.commit();
            ctx.getWriteCount().addAndGet(buffer.size());
        } catch (Exception batchEx) {
            conn.rollback();
            ps.clearBatch();
            ctx.log("WARN", "批写失败,回退逐行定位坏行: " + batchEx.getMessage());
            int ok = 0;
            for (Object[] row : buffer) {
                try {
                    for (int i = 0; i < writeColCount; i++) ps.setObject(i + 1, row[i]);
                    ps.executeUpdate();
                    ok++;
                } catch (Exception rowEx) {
                    long dirty = ctx.getDirtyCount().incrementAndGet();
                    ctx.log("ERROR", "坏行丢弃(累计脏数据=" + dirty + "): " + rowEx.getMessage());
                    // DS-M1：坏行落 DLQ(失败不阻断同步)
                    try { ctx.getBadRowSink().accept(sourceTable, row, rowEx.getMessage()); } catch (Exception ignore) {}
                    if (exceeded(dirty, ctx.getReadCount().get(), ctx.getErrorLimitRows(), ctx.getErrorLimitRatio())) {
                        conn.commit();
                        ctx.getWriteCount().addAndGet(ok);
                        throw new DirtyDataExceededException("脏数据超阈值,累计=" + dirty);
                    }
                }
            }
            conn.commit();
            ctx.getWriteCount().addAndGet(ok);
        } finally {
            ps.clearBatch();
            buffer.clear();
        }
    }

    /** 脏数据是否超阈值(条数 OR 比率,任一触发;均 null=不限)。 */
    public static boolean exceeded(long dirty, long read, Integer limitRows, Double limitRatio) {
        if (limitRows != null && dirty > limitRows) return true;
        if (limitRatio != null && read > 0 && (double) dirty / read > limitRatio) return true;
        return false;
    }
}
