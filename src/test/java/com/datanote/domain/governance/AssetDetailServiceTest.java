package com.datanote.domain.governance;

import com.datanote.domain.governance.AssetDetailService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资产详情纯函数单测 —— Profiler 空值率格式化 / 字段限流，无 Spring 上下文。
 */
class AssetDetailServiceTest {

    // ========== 空值率格式化 ==========

    @Test
    void formatRateZeroWhenTotalZero() {
        assertEquals("0%", AssetDetailService.formatRate(0, 0));
        assertEquals("0%", AssetDetailService.formatRate(5, 0));
    }

    @Test
    void formatRateOneDecimal() {
        // 3/8 = 37.5%
        assertEquals("37.5%", AssetDetailService.formatRate(3, 8));
        // 0/10 = 0.0%
        assertEquals("0.0%", AssetDetailService.formatRate(0, 10));
        // 全空 10/10 = 100.0%
        assertEquals("100.0%", AssetDetailService.formatRate(10, 10));
    }

    // ========== Profiler 字段限流 ==========

    @Test
    void limitFieldsCapsAtMax() {
        assertEquals(30, AssetDetailService.limitFields(100, 30));
        assertEquals(30, AssetDetailService.limitFields(30, 30));
    }

    @Test
    void limitFieldsKeepsWhenBelowMax() {
        assertEquals(5, AssetDetailService.limitFields(5, 30));
        assertEquals(0, AssetDetailService.limitFields(0, 30));
        assertEquals(0, AssetDetailService.limitFields(-3, 30));
    }
}
