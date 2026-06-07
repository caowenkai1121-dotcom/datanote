package com.datanote.domain.governance;

import com.datanote.domain.governance.ClassificationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分类→表级敏感标签 幂等增删纯函数单测（接点③）。
 */
class ClassificationTagTest {

    private static final String TAG = "含敏感字段";

    @Test
    void addToNullOrEmpty() {
        assertEquals(TAG, ClassificationService.applySensitiveTag(null, true));
        assertEquals(TAG, ClassificationService.applySensitiveTag("", true));
        assertEquals(TAG, ClassificationService.applySensitiveTag("   ", true));
    }

    @Test
    void addKeepsOthersAndOrder() {
        assertEquals("核心,DWD," + TAG, ClassificationService.applySensitiveTag("核心,DWD", true));
    }

    @Test
    void idempotentWhenAlreadyPresent() {
        assertEquals("核心," + TAG, ClassificationService.applySensitiveTag("核心," + TAG, true));
        assertEquals(TAG, ClassificationService.applySensitiveTag(TAG, true));
    }

    @Test
    void removeWhenNoLongerSensitive() {
        assertEquals("核心,DWD", ClassificationService.applySensitiveTag("核心," + TAG + ",DWD", false));
        assertEquals("", ClassificationService.applySensitiveTag(TAG, false));
        assertEquals("", ClassificationService.applySensitiveTag(null, false));
    }

    @Test
    void handlesFullwidthCommaAndWhitespace() {
        // 中文逗号分隔 + 空格，重新归一为半角逗号 CSV
        assertEquals("核心,DWD," + TAG, ClassificationService.applySensitiveTag(" 核心，DWD ", true));
    }
}
