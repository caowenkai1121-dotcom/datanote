package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理中心插件契约：模块 UI 以独立 js/gov-&lt;key&gt;.js 自注册到 GOV_RENDERERS，
 * governance.html 预包含全部脚本（缺失文件 404 无害）。保障后续里程碑只新增 js 文件即可挂载。
 */
class GovernancePluginTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void preIncludesAllModuleScripts() throws Exception {
        String html = read("src/main/resources/static/governance.html");
        String[] scripts = {"js/gov-standard.js", "js/gov-classification.js",
                "js/gov-security.js", "js/gov-health.js", "js/gov-quality.js", "js/gov-audit.js"};
        for (String s : scripts) {
            assertTrue(html.contains(s), "治理入口应预包含 " + s + " 以便对应里程碑挂载");
        }
    }

    @Test
    void usesRendererRegistry() throws Exception {
        assertTrue(read("src/main/resources/static/governance.html").contains("GOV_RENDERERS"),
                "治理入口应据 GOV_RENDERERS 注册表渲染");
        assertTrue(read("src/main/resources/static/js/dn-common.js").contains("GOV_RENDERERS"),
                "公共层应初始化 GOV_RENDERERS 注册表");
    }
}
