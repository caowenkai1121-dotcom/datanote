package com.datanote.domain.integration.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BatchWriterThresholdTest {
    @Test void noLimitNeverExceeds() { assertFalse(BatchWriter.exceeded(1000, 1000, null, null)); }
    @Test void rowsLimit() {
        assertFalse(BatchWriter.exceeded(5, 100, 10, null));
        assertTrue(BatchWriter.exceeded(11, 100, 10, null));
    }
    @Test void ratioLimit() {
        assertFalse(BatchWriter.exceeded(5, 100, null, 0.1));
        assertTrue(BatchWriter.exceeded(11, 100, null, 0.1));
    }
    @Test void eitherTriggers() { assertTrue(BatchWriter.exceeded(11, 100, 10, 0.5)); }
}
