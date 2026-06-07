package com.datanote.domain.integration.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class RateLimiterTest {
    @Test void tokensAvailableNoWait() {
        RateLimiter r = new RateLimiter(100, 0L);
        assertEquals(0L, r.reserve(50, 0L));
    }
    @Test void deficitWaits() {
        RateLimiter r = new RateLimiter(100, 0L);
        r.reserve(100, 0L);
        long w = r.reserve(100, 0L);
        assertTrue(w > 0);
        assertEquals(1_000_000_000L, w, 2_000_000L);
    }
    @Test void refillOverTime() {
        RateLimiter r = new RateLimiter(100, 0L);
        r.reserve(100, 0L);
        assertEquals(0L, r.reserve(50, 500_000_000L));
    }
}
