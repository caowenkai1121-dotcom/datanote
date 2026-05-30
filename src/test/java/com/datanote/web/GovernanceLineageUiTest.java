package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceLineageUiTest {

    private static String gov() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/static/governance.html")), StandardCharsets.UTF_8);
    }

    @Test
    void lineageModuleLiveAndWired() throws Exception {
        String html = gov();
        assertTrue(html.contains("renderLineage"), "血缘模块应有渲染函数");
        assertTrue(html.contains("/api/lineage/rebuild-edges"), "应能重建血缘边");
        assertTrue(html.contains("/api/lineage/table-edges"), "应能查表级上下游");
        assertTrue(html.contains("/api/lineage/column-edges"), "应能查字段入边");
    }
}
