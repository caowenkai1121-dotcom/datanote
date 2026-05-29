package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class BackoffCalculatorTest {
    @Test void fixedDelay() {
        assertEquals(5, BackoffCalculator.delaySeconds(1,"FIXED_DELAY",5,300));
        assertEquals(5, BackoffCalculator.delaySeconds(3,"FIXED_DELAY",5,300));
    }
    @Test void exponential() {
        assertEquals(5, BackoffCalculator.delaySeconds(1,"EXPONENTIAL",5,300));
        assertEquals(10, BackoffCalculator.delaySeconds(2,"EXPONENTIAL",5,300));
        assertEquals(20, BackoffCalculator.delaySeconds(3,"EXPONENTIAL",5,300));
    }
    @Test void exponentialCapped() { assertEquals(300, BackoffCalculator.delaySeconds(10,"EXPONENTIAL",5,300)); }
    @Test void nullTypeDefaultsFixed() { assertEquals(5, BackoffCalculator.delaySeconds(2,null,5,300)); }
}
