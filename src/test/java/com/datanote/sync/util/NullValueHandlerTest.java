package com.datanote.domain.integration.util;

import com.datanote.domain.integration.dto.FieldMapping;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NullValueHandlerTest {

    private FieldMapping fm(String nh, String def) {
        FieldMapping f = new FieldMapping();
        f.setNullHandling(nh);
        f.setDefaultValue(def);
        return f;
    }

    @Test
    void nonNullPassthrough() {
        assertEquals("x", NullValueHandler.handle("x", fm("REPLACE_WITH_DEFAULT", "D")));
    }

    @Test
    void nullPassthroughByDefault() {
        assertNull(NullValueHandler.handle(null, null));
        assertNull(NullValueHandler.handle(null, fm("PASSTHROUGH", null)));
    }

    @Test
    void nullReplacedWithDefault() {
        assertEquals("D", NullValueHandler.handle(null, fm("REPLACE_WITH_DEFAULT", "D")));
    }

    @Test
    void nullSkipRowReturnsSentinel() {
        assertSame(NullValueHandler.SKIP, NullValueHandler.handle(null, fm("SKIP_ROW", null)));
    }
}
