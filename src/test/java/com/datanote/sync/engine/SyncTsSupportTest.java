package com.datanote.sync.engine;

import com.datanote.sync.dto.SyncContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步时间戳列追加判断单测：标记开关、已存在列、非法列名配置期校验。
 */
class SyncTsSupportTest {

    private static SyncContext ctx(Integer mark, String field) {
        SyncContext c = new SyncContext();
        c.setMarkSyncTs(mark);
        c.setSyncTsField(field);
        return c;
    }

    @Test
    void markOff_returnsFalse() {
        assertFalse(SyncTsSupport.shouldAppend(ctx(0, "sync_ts"), null));
        assertFalse(SyncTsSupport.shouldAppend(ctx(null, "sync_ts"), null));
        assertFalse(SyncTsSupport.shouldAppend(ctx(1, ""), null));
    }

    @Test
    void validField_appendsWhenAbsent() {
        assertTrue(SyncTsSupport.shouldAppend(ctx(1, "sync_ts"), Collections.emptyList()));
        // 已存在同名目标列则不重复追加
        assertFalse(SyncTsSupport.shouldAppend(ctx(1, "sync_ts"), Arrays.asList("id", "sync_ts")));
    }

    @Test
    void illegalField_throwsAtConfigTime() {
        assertThrows(IllegalStateException.class,
                () -> SyncTsSupport.shouldAppend(ctx(1, "bad name!"), Collections.emptyList()));
    }
}
