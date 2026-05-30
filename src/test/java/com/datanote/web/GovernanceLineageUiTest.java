package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceLineageUiTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void lineageModuleRegistersAndCallsApis() throws Exception {
        String js = read("src/main/resources/static/js/gov-lineage.js");
        assertTrue(js.contains("GOV_RENDERERS.lineage"), "血缘模块应注册到 GOV_RENDERERS");
        assertTrue(js.contains("/api/lineage/rebuild-edges"), "应能重建血缘边");
        assertTrue(js.contains("/api/lineage/table-edges"), "应能查表级上下游");
        assertTrue(js.contains("/api/lineage/column-edges"), "应能查字段入边");
    }

    @Test
    void governanceIncludesLineageScript() throws Exception {
        assertTrue(read("src/main/resources/static/workspace.html").contains("js/gov-lineage.js"),
                "治理入口应预包含 gov-lineage.js");
    }
}
