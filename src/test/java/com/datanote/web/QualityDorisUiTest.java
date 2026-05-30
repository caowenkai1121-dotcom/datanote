package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityDorisUiTest {

    private static String ws() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/static/workspace.html")), StandardCharsets.UTF_8);
    }

    @Test
    void datasourceDropdownOffersDorisWarehouse() throws Exception {
        assertTrue(ws().contains("value=\"0\">Doris 数仓"),
                "质量规则数据源下拉应提供 Doris 数仓(value=0) 选项");
    }

    @Test
    void warehouseUsesMetadataEndpoints() throws Exception {
        String html = ws();
        assertTrue(html.contains("/api/metadata/databases"), "数仓应通过元数据接口取库");
        assertTrue(html.contains("/api/metadata/tables?db="), "数仓应通过元数据接口取表");
        assertTrue(html.contains("/api/metadata/columns?db="), "数仓应通过元数据接口取字段");
    }

    @Test
    void saveValidationAllowsZeroDatasource() throws Exception {
        assertTrue(ws().contains("payload.datasourceId === null"),
                "保存校验应用 ===null 判定而非 falsy，放行 datasourceId=0");
    }
}
