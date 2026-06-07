package com.datanote.domain.consumption;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标值引擎纯函数单测（R10 消费层）：取值解析 / 结果首格提取 / 新鲜度判定 / 存活小时。
 */
class MetricValueServiceTest {

    @Test
    void parseMetricValue_numericOrNull() {
        assertEquals(new BigDecimal("123.45"), MetricValueService.parseMetricValue("123.45"));
        assertEquals(new BigDecimal("0"), MetricValueService.parseMetricValue("0"));
        assertEquals(new BigDecimal("-5"), MetricValueService.parseMetricValue(" -5 "));
        assertNull(MetricValueService.parseMetricValue(null));
        assertNull(MetricValueService.parseMetricValue(""));
        assertNull(MetricValueService.parseMetricValue("NULL"));
        assertNull(MetricValueService.parseMetricValue("abc"));   // 非数字 → null(落 value_text)
    }

    @Test
    void firstCell_fromExecResultRows() {
        Map<String, Object> r = new HashMap<>();
        r.put("rows", Collections.singletonList(Arrays.asList("42", "x")));
        assertEquals("42", MetricValueService.firstCell(r));

        assertNull(MetricValueService.firstCell(null));
        Map<String, Object> empty = new HashMap<>();
        empty.put("rows", Collections.emptyList());
        assertNull(MetricValueService.firstCell(empty));
        Map<String, Object> noRows = new HashMap<>();
        assertNull(MetricValueService.firstCell(noRows));
    }

    @Test
    void isStale_byThreshold() {
        assertFalse(MetricValueService.isStale(0));
        assertFalse(MetricValueService.isStale(25));
        assertTrue(MetricValueService.isStale(26));   // FRESH_HOURS=26
        assertTrue(MetricValueService.isStale(100));
        assertTrue(MetricValueService.isStale(-1));    // 无值=陈旧
    }

    @Test
    void ageHours_nonNegative() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 7, 12, 0);
        assertEquals(3L, MetricValueService.ageHours(now.minusHours(3), now));
        assertEquals(0L, MetricValueService.ageHours(now.plusHours(5), now)); // 未来→0
        assertEquals(0L, MetricValueService.ageHours(null, now));
    }
}
