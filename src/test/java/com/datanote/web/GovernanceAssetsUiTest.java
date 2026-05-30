package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceAssetsUiTest {

    private static String gov() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/static/governance.html")), StandardCharsets.UTF_8);
    }

    @Test
    void assetsModuleIsLiveAndCallsCrawlAndList() throws Exception {
        String html = gov();
        assertTrue(html.contains("renderAssets"), "资产目录应有专门渲染函数");
        assertTrue(html.contains("/api/metadata-center/crawl/all"), "应能触发全量采集");
        assertTrue(html.contains("/api/metadata-center/collect-logs"), "应能查询采集日志");
        assertTrue(html.contains("/api/metadata-center/tables"), "应能列出资产表元数据");
    }
}
