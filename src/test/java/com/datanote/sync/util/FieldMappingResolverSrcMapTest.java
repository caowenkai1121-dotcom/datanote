package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class FieldMappingResolverSrcMapTest {
    private FieldMapping fm(String s, String t, boolean sync, String nh) {
        FieldMapping f = new FieldMapping();
        f.setSource(s); f.setTarget(t); f.setSync(sync); f.setNullHandling(nh);
        return f;
    }
    @Test void emptyFieldsGivesEmptyMap() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r =
            FieldMappingResolver.resolve(tc, Arrays.asList("id","name"), "id");
        assertNotNull(r.srcToFieldMapping);
        assertTrue(r.srcToFieldMapping.isEmpty());
    }
    @Test void mapKeyedBySourceColumn() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        tc.setFields(Arrays.asList(fm("id","id",true,null), fm("name","nm",true,"SKIP_ROW")));
        FieldMappingResolver.Resolved r =
            FieldMappingResolver.resolve(tc, Arrays.asList("id","name"), "id");
        assertEquals("SKIP_ROW", r.srcToFieldMapping.get("name").getNullHandling());
        assertTrue(r.srcToFieldMapping.containsKey("id"));
    }
}
