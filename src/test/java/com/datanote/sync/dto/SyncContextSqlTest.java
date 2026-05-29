package com.datanote.sync.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyncContextSqlTest {
    private TableSyncConfig tc(String src, String pre, String post) {
        TableSyncConfig t = new TableSyncConfig();
        t.setSourceTable(src); t.setPreSql(pre); t.setPostSql(post);
        return t;
    }
    @Test void tableLevelOverridesGlobal() {
        SyncContext ctx = new SyncContext();
        ctx.setGlobalPreSql("GP"); ctx.setGlobalPostSql("GQ");
        assertEquals("TP", ctx.getPreSql(tc("t","TP",null)));
        assertEquals("TQ", ctx.getPostSql(tc("t",null,"TQ")));
    }
    @Test void fallbackToGlobalWhenTableBlank() {
        SyncContext ctx = new SyncContext();
        ctx.setGlobalPreSql("GP"); ctx.setGlobalPostSql("GQ");
        assertEquals("GP", ctx.getPreSql(tc("t",null,null)));
        assertEquals("GP", ctx.getPreSql(tc("t","  ",null)));
        assertEquals("GQ", ctx.getPostSql(tc("t",null,"")));
    }
    @Test void nullWhenBothBlank() {
        SyncContext ctx = new SyncContext();
        assertNull(ctx.getPreSql(tc("t",null,null)));
        assertNull(ctx.getPostSql(tc("t",null,null)));
    }
}
