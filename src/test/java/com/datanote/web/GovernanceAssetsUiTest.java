package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceAssetsUiTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void assetsModuleRegistersAndCallsApis() throws Exception {
        String js = read("src/main/resources/static/js/gov-assets.js");
        assertTrue(js.contains("GOV_RENDERERS.assets"), "资产模块应注册到 GOV_RENDERERS");
        assertTrue(js.contains("/api/metadata-center/crawl/all"), "应能触发全量采集");
        assertTrue(js.contains("/api/metadata-center/collect-logs"), "应能查询采集日志");
        assertTrue(js.contains("/api/metadata-center/tables"), "应能列出资产表元数据");
    }

    @Test
    void governanceIncludesAssetsScript() throws Exception {
        assertTrue(read("src/main/resources/static/workspace.html").contains("js/gov-assets.js"),
                "治理入口应预包含 gov-assets.js");
    }
}
