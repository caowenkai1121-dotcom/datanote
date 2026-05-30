package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理健康分模块前端契约：gov-health.js 注册到 GOV_RENDERERS.health 并调用 M11 端点。
 */
class GovernanceHealthUiTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void healthModuleRegistersAndCallsApis() throws Exception {
        String js = read("src/main/resources/static/js/gov-health.js");
        assertTrue(js.contains("GOV_RENDERERS.health"), "应注册到 GOV_RENDERERS.health");
        assertTrue(js.contains("/api/gov/health/score"), "应查询健康分");
        assertTrue(js.contains("/api/gov/health/dimensions"), "应查询五维明细");
        assertTrue(js.contains("/api/gov/health/issues"), "应管理工单");
        assertTrue(js.contains("transition"), "应支持工单状态流转");
        assertTrue(js.contains("/api/gov/health/maturity"), "应支持 DCMM 成熟度自评");
    }

    @Test
    void governanceIncludesHealthScript() throws Exception {
        assertTrue(read("src/main/resources/static/governance.html").contains("js/gov-health.js"),
                "治理入口应预包含 gov-health.js");
    }
}
