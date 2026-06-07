package com.datanote.domain.integration.util;

import com.datanote.domain.integration.dto.FieldMapping;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class RowValueProcessorTest {

    private FieldMapping fm(String source, boolean sync, String nullHandling,
                            String defaultValue, String transformExpression, String maskingType) {
        FieldMapping f = new FieldMapping();
        f.setSource(source);
        f.setSync(sync);
        f.setNullHandling(nullHandling);
        f.setDefaultValue(defaultValue);
        f.setTransformExpression(transformExpression);
        f.setMaskingType(maskingType);
        return f;
    }

    @Test
    void emptyMapIsNoop() {
        RowValueProcessor p = new RowValueProcessor(Collections.emptyMap());
        Object[] raw = {"a", "b", "c"};
        assertArrayEquals(raw, p.process(Arrays.asList("c1", "c2", "c3"), raw));
    }

    @Test
    void defaultAndUpperAndMask() {
        Map<String, FieldMapping> map = new HashMap<>();
        map.put("name",  fm("name",  true, null,                    null,      "{\"type\":\"upper\"}", null));
        map.put("city",  fm("city",  true, "REPLACE_WITH_DEFAULT",  "UNKNOWN", null,                  null));
        map.put("phone", fm("phone", true, null,                    null,      null,                  "PHONE"));

        List<String> cols = Arrays.asList("name", "city", "phone");
        Object[] raw = {"bob", null, "13812348000"};

        RowValueProcessor p = new RowValueProcessor(map);
        Object[] out = p.process(cols, raw);

        assertNotNull(out);
        assertEquals("BOB",         out[0]);
        assertEquals("UNKNOWN",     out[1]);
        assertEquals("138****8000", out[2]);
    }

    @Test
    void skipRowReturnsNull() {
        Map<String, FieldMapping> map = new HashMap<>();
        map.put("name", fm("name", true, "SKIP_ROW", null, null, null));

        List<String> cols = Arrays.asList("name");
        Object[] raw = {null};

        RowValueProcessor p = new RowValueProcessor(map);
        assertNull(p.process(cols, raw));
    }

    // === 防御性 bug 修复用例（Group B）===

    @Test
    void emptyMapReturnsSameReference() {
        RowValueProcessor p = new RowValueProcessor(Collections.emptyMap());
        Object[] raw = {"a", "b"};
        assertSame(raw, p.process(Arrays.asList("c1", "c2"), raw));
    }
}
