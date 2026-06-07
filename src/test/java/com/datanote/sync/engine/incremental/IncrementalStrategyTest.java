package com.datanote.domain.integration.engine.incremental;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalStrategyTest {

    @Test
    void autoIncrement_comparesNumerically() {
        IncrementalStrategy s = new AutoIncrementStrategy();
        assertEquals("AUTO_INCREMENT", s.type());
        assertTrue(s.compare("100", "20") > 0);
        assertTrue(s.compare("5", "5") == 0);
        assertEquals("42", s.toStored(42L));
    }

    @Test
    void timestamp_comparesAsString() {
        IncrementalStrategy s = new TimestampStrategy();
        assertEquals("TIMESTAMP", s.type());
        assertTrue(s.compare("2026-01-02 00:00:00", "2026-01-01 23:59:59") > 0);
        assertEquals("2026-01-01 10:00:00", s.toStored("2026-01-01 10:00:00"));
    }

    @Test
    void factory_returnsByType() {
        assertTrue(IncrementalStrategyFactory.get("AUTO_INCREMENT") instanceof AutoIncrementStrategy);
        assertTrue(IncrementalStrategyFactory.get("TIMESTAMP") instanceof TimestampStrategy);
        assertTrue(IncrementalStrategyFactory.get(null) instanceof TimestampStrategy);
    }
}
