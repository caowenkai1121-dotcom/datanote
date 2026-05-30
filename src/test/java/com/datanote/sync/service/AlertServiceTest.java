package com.datanote.sync.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class AlertServiceTest {
    @Test void firstSendNotThrottled() { assertFalse(AlertService.throttled(null, 1000L, 30)); }
    @Test void withinWindowThrottled() { assertTrue(AlertService.throttled(1000L, 1000L + 60_000L, 30)); }
    @Test void afterWindowNotThrottled() { assertFalse(AlertService.throttled(1000L, 1000L + 31L*60_000L, 30)); }
}
