package com.datanote.domain.consumption;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标预警阈值判定纯函数单测（R12）。
 */
class MetricAlertServiceTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void breach_comparators() {
        assertTrue(MetricAlertService.isBreach("GT", bd("10"), bd("5"), null));
        assertFalse(MetricAlertService.isBreach("GT", bd("3"), bd("5"), null));
        assertTrue(MetricAlertService.isBreach("LT", bd("3"), bd("5"), null));
        assertTrue(MetricAlertService.isBreach("GE", bd("5"), bd("5"), null));
        assertTrue(MetricAlertService.isBreach("LE", bd("5"), bd("5"), null));
        assertTrue(MetricAlertService.isBreach("NE", bd("4"), bd("5"), null));
        assertFalse(MetricAlertService.isBreach("NE", bd("5"), bd("5"), null));
    }

    @Test
    void breach_range() {
        assertTrue(MetricAlertService.isBreach("OUT", bd("11"), bd("0"), bd("10")), "超上界");
        assertTrue(MetricAlertService.isBreach("OUT", bd("-1"), bd("0"), bd("10")), "超下界");
        assertFalse(MetricAlertService.isBreach("OUT", bd("5"), bd("0"), bd("10")), "区间内不报");
        assertTrue(MetricAlertService.isBreach("IN", bd("5"), bd("0"), bd("10")), "落入禁区报");
        assertFalse(MetricAlertService.isBreach("IN", bd("11"), bd("0"), bd("10")));
    }

    @Test
    void breach_nullSafe() {
        assertFalse(MetricAlertService.isBreach(null, bd("5"), bd("1"), null));
        assertFalse(MetricAlertService.isBreach("GT", null, bd("1"), null));
        assertFalse(MetricAlertService.isBreach("GT", bd("5"), null, null));
        assertFalse(MetricAlertService.isBreach("OUT", bd("5"), bd("1"), null), "区间缺上界不报");
        assertFalse(MetricAlertService.isBreach("XX", bd("5"), bd("1"), null), "未知op不报");
    }

    @Test
    void desc_and_severity() {
        assertEquals("> 5", MetricAlertService.breachDesc("GT", bd("5"), null));
        assertEquals("超出区间 [0, 10]", MetricAlertService.breachDesc("OUT", bd("0"), bd("10")));
        assertEquals("HIGH", MetricAlertService.normalizeSeverity(" high "));
        assertEquals("MEDIUM", MetricAlertService.normalizeSeverity(null));
        assertEquals("MEDIUM", MetricAlertService.normalizeSeverity("bogus"));
    }
}
