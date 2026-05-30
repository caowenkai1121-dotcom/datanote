package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类分级模块前端契约：gov-classification.js 注册到 GOV_RENDERERS 并调用 M8 端点。
 */
class GovernanceClassificationUiTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void classificationModuleRegistersAndCallsApis() throws Exception {
        String js = read("src/main/resources/static/js/gov-classification.js");
        assertTrue(js.contains("GOV_RENDERERS.classification"), "应注册到 GOV_RENDERERS.classification");
        assertTrue(js.contains("/api/gov/classification/levels"), "应查询分级模型");
        assertTrue(js.contains("/api/gov/classification/rules"), "应管理敏感规则");
        assertTrue(js.contains("/api/gov/classification/scan"), "应能对表采样识别");
        assertTrue(js.contains("/api/gov/classification/confirm"), "应能人工确认打标");
    }

    @Test
    void governanceIncludesClassificationScript() throws Exception {
        assertTrue(read("src/main/resources/static/workspace.html").contains("js/gov-classification.js"),
                "治理入口应预包含 gov-classification.js");
    }
}
