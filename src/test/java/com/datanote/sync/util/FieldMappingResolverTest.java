package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FieldMappingResolver 纯逻辑单测：空 fields 全列回退、选择+重命名、主键校验。
 */
class FieldMappingResolverTest {

    private final List<String> allColumns = Arrays.asList("id", "name", "age", "addr");

    private static FieldMapping fm(String src, String tgt, Boolean sync) {
        FieldMapping f = new FieldMapping();
        f.setSource(src);
        f.setTarget(tgt);
        f.setSync(sync);
        return f;
    }

    @Test
    void nullFields_fallsBackToAllColumns() {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, allColumns, "id");
        assertEquals(allColumns, r.srcColumns);
        assertEquals(allColumns, r.tgtColumns);
        assertEquals("id", r.pkTarget);
    }

    @Test
    void emptyFields_fallsBackToAllColumns() {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("t");
        tc.setFields(new ArrayList<>());
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, allColumns, "id");
        assertEquals(allColumns, r.srcColumns);
        assertEquals("id", r.pkTarget);
    }

    @Test
    void selectsAndRenames_onlySyncTrue() {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("t");
        tc.setFields(Arrays.asList(
                fm("id", "uid", true),
                fm("name", "uname", true),
                fm("age", "age", false),   // 不同步
                fm("addr", null, true)     // target 空 -> 回退 source
        ));
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, allColumns, "id");
        assertEquals(Arrays.asList("id", "name", "addr"), r.srcColumns);
        assertEquals(Arrays.asList("uid", "uname", "addr"), r.tgtColumns);
        assertEquals("uid", r.pkTarget);
    }

    @Test
    void throwsWhenPkSourceNotSelected() {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("t");
        tc.setFields(Arrays.asList(
                fm("name", "uname", true),
                fm("age", "age", true)
        ));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FieldMappingResolver.resolve(tc, allColumns, "id"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("主键列 id"));
    }

    @Test
    void throwsWhenNoFieldSelected() {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("t");
        tc.setFields(Arrays.asList(
                fm("id", "id", false),
                fm("name", "name", false)
        ));
        assertThrows(IllegalStateException.class,
                () -> FieldMappingResolver.resolve(tc, allColumns, "id"));
    }

    @Test
    void throwsWhenDuplicateTarget() {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("t");
        // 两个源列映射到同一目标列 dup -> 解析期应拒绝
        tc.setFields(Arrays.asList(
                fm("id", "id", true),
                fm("name", "dup", true),
                fm("addr", "dup", true)
        ));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FieldMappingResolver.resolve(tc, allColumns, "id"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("重复的目标列"));
    }
}
