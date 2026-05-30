package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

public class FieldMappingResolverPkListTest {

    @Test void multiPkNoMapping() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("a","b","c"), Arrays.asList("a","b"));
        assertEquals(Arrays.asList("a","b"), r.pkSourceColumns);
        assertEquals(Arrays.asList("a","b"), r.pkTargetColumns);
    }

    @Test void noPkEmptyLists() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("a","b"), java.util.Collections.emptyList());
        assertTrue(r.pkSourceColumns.isEmpty());
        assertTrue(r.pkTargetColumns.isEmpty());
        assertNull(r.pkTarget);
    }

    @Test void multiPkWithMappingRenamesTarget() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMapping a=new FieldMapping(); a.setSource("a"); a.setTarget("A"); a.setSync(true);
        FieldMapping b=new FieldMapping(); b.setSource("b"); b.setTarget("B"); b.setSync(true);
        tc.setFields(Arrays.asList(a,b));
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("a","b"), Arrays.asList("a","b"));
        assertEquals(Arrays.asList("a","b"), r.pkSourceColumns);
        assertEquals(Arrays.asList("A","B"), r.pkTargetColumns);
    }

    @Test void singlePkBackCompat() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("id","n"), "id");
        assertEquals("id", r.pkTarget);
        assertEquals(Arrays.asList("id"), r.pkSourceColumns);
    }
}
