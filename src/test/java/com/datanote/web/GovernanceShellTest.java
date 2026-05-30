package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceShellTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void dnCommonExposesApiHelpers() throws Exception {
        String js = read("src/main/resources/static/js/dn-common.js");
        assertTrue(js.contains("DN.api ="), "公共层应暴露 DN.api");
        assertTrue(js.contains("DN.post ="), "公共层应暴露 DN.post");
        assertTrue(js.contains("body.code === 0"), "DN.api 应解析 R 信封 code===0");
    }

    @Test
    void governanceHtmlHasNavAndLoadsCommon() throws Exception {
        String html = read("src/main/resources/static/governance.html");
        assertTrue(html.contains("js/dn-common.js"), "治理入口应加载 dn-common.js");
        assertTrue(html.contains("GOV_MODULES"), "治理入口应有模块注册表");
        assertTrue(html.contains("数据标准") && html.contains("分类分级") && html.contains("治理健康分"),
                "导航应含规划中的治理模块");
        assertTrue(html.contains("workspace.html#/quality"), "质量模块应链入工作台质量路由");
    }

    @Test
    void workspaceRoutesGovernanceToHubAndKeepsQuality() throws Exception {
        String html = read("src/main/resources/static/workspace.html");
        assertTrue(html.contains("'governance':  { view: 'viewGovernance'"),
                "#/governance 应指向治理启动页 viewGovernance");
        assertTrue(html.contains("'quality':"),
                "应新增 #/quality 路由保证质量页可达");
    }

    @Test
    void governanceHubCardsAreWired() throws Exception {
        String html = read("src/main/resources/static/workspace.html");
        assertTrue(html.contains("href=\"#/quality\""), "数据质量卡片应链到 #/quality");
        assertTrue(html.contains("href=\"governance.html#standard\""), "数据标准卡片应链到 governance.html");
        assertTrue(html.contains("href=\"governance.html#security\""), "安全管理卡片应链到 governance.html");
    }
}
