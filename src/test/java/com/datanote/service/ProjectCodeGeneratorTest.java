package com.datanote.service;

import com.datanote.domain.project.ProjectCodeGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PM-M1：项目编码规范化纯函数。 */
public class ProjectCodeGeneratorTest {

    @Test
    void asciiUppercased() {
        assertEquals("SALESDW", ProjectCodeGenerator.slug("salesDW"));
        assertEquals("PROJ2024", ProjectCodeGenerator.slug("proj 2024"));
    }

    @Test
    void keepsUnderscoreAndDash() {
        assertEquals("DATA_LAKE", ProjectCodeGenerator.slug("data-lake"));
        assertEquals("A_B", ProjectCodeGenerator.slug("a_b"));
    }

    @Test
    void chineseOrEmptyFallsBack() {
        assertEquals("PROJ", ProjectCodeGenerator.slug("销售数仓"));
        assertEquals("PROJ", ProjectCodeGenerator.slug(""));
        assertEquals("PROJ", ProjectCodeGenerator.slug(null));
        assertEquals("PROJ", ProjectCodeGenerator.slug("___"));
    }

    @Test
    void mixedChineseAsciiKeepsAscii() {
        assertEquals("DW", ProjectCodeGenerator.slug("数仓DW"));
    }

    @Test
    void cappedLength() {
        assertTrue(ProjectCodeGenerator.slug("abcdefghijklmnopqrstuvwxyz0123456789").length() <= 24);
    }
}
