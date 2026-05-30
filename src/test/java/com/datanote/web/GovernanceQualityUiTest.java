package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceQualityUiTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void qualityModuleRegistersAndCallsApis() throws Exception {
        String js = read("src/main/resources/static/js/gov-quality.js");
        assertTrue(js.contains("GOV_RENDERERS.quality"), "质量模块应注册到 GOV_RENDERERS");
        assertTrue(js.contains("/api/quality/score"), "应展示整体质量分");
        assertTrue(js.contains("/api/quality/trend"), "应展示通过率趋势");
        assertTrue(js.contains("/api/quality/rules"), "应列出质量规则");
        assertTrue(js.contains("workspace.html#/quality"), "应提供前往工作台质量链接");
    }

    @Test
    void governanceIncludesQualityScript() throws Exception {
        assertTrue(read("src/main/resources/static/workspace.html").contains("js/gov-quality.js"),
                "治理入口应预包含 gov-quality.js");
    }
}
