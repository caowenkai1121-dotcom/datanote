package com.datanote.sync.engine;

import com.datanote.sync.dto.SyncContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DS-M1：批写回退逐行定位坏行时，坏行应落 badRowSink（含源表/原始行/错误信息），好行照常计入 writeCount。 */
public class BatchWriterSinkTest {

    @Test
    void badRowGoesToSink() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        // 批写失败 -> 回退逐行；第一行成功，第二行抛错 -> 坏行
        when(ps.executeBatch()).thenThrow(new SQLException("batch boom"));
        when(ps.executeUpdate()).thenReturn(1).thenThrow(new SQLException("row boom"));

        SyncContext ctx = new SyncContext();
        List<String> capTbl = new ArrayList<>();
        List<Object[]> capRow = new ArrayList<>();
        List<String> capMsg = new ArrayList<>();
        ctx.setBadRowSink((tbl, row, err) -> { capTbl.add(tbl); capRow.add(row); capMsg.add(err); });

        BatchWriter bw = new BatchWriter(ps, conn, ctx, Arrays.asList("id", "name"), "appdb.users", "ods.users");
        bw.add(new Object[]{1, "ok"});
        bw.add(new Object[]{2, "bad"});
        bw.flush();

        assertEquals(1, capTbl.size(), "应只落 1 条坏行");
        assertEquals("appdb.users", capTbl.get(0));
        assertEquals("row boom", capMsg.get(0));
        assertEquals(2, capRow.get(0)[0], "坏行原始数据应透传");
        assertEquals(1L, ctx.getDirtyCount().get());
        assertEquals(1L, ctx.getWriteCount().get(), "好行应计入 writeCount");
    }

    @Test
    void sinkFailureDoesNotBreakSync() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        when(ps.executeBatch()).thenThrow(new SQLException("batch boom"));
        when(ps.executeUpdate()).thenThrow(new SQLException("row boom"));

        SyncContext ctx = new SyncContext();
        ctx.setBadRowSink((tbl, row, err) -> { throw new RuntimeException("DLQ down"); });

        BatchWriter bw = new BatchWriter(ps, conn, ctx, Arrays.asList("id"), "appdb.t", "ods.t");
        bw.add(new Object[]{1});
        // sink 抛错被吞，flush 不应抛出
        assertDoesNotThrow(bw::flush);
        assertEquals(1L, ctx.getDirtyCount().get());
    }
}
