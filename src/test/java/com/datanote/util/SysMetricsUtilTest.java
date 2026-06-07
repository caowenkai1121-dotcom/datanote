package com.datanote.util;

import com.datanote.platform.portal.util.SysMetricsUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SysMetricsUtilTest {

    @Test
    void usagePct_basic() {
        assertEquals(0, SysMetricsUtil.usagePct(0, 0));
        assertEquals(0, SysMetricsUtil.usagePct(10, 0));
        assertEquals(50, SysMetricsUtil.usagePct(50, 100));
        assertEquals(90, SysMetricsUtil.usagePct(900, 1000));
        assertEquals(0, SysMetricsUtil.usagePct(0, 100));
    }

    @Test
    void usagePct_clamp() {
        assertEquals(100, SysMetricsUtil.usagePct(150, 100));
        assertEquals(0, SysMetricsUtil.usagePct(-5, 100));
    }

    @Test
    void usageLevel_thresholds() {
        assertEquals("ok", SysMetricsUtil.usageLevel(0));
        assertEquals("ok", SysMetricsUtil.usageLevel(74));
        assertEquals("warn", SysMetricsUtil.usageLevel(75));
        assertEquals("warn", SysMetricsUtil.usageLevel(89));
        assertEquals("danger", SysMetricsUtil.usageLevel(90));
        assertEquals("danger", SysMetricsUtil.usageLevel(100));
    }

    @Test
    void humanDuration_units() {
        assertEquals("30秒", SysMetricsUtil.humanDuration(30_000));
        assertEquals("2分", SysMetricsUtil.humanDuration(120_000));
        assertEquals("1小时5分", SysMetricsUtil.humanDuration(3_900_000));
        assertEquals("1天2小时", SysMetricsUtil.humanDuration(93_600_000));
        assertEquals("0秒", SysMetricsUtil.humanDuration(-100));
    }
}
