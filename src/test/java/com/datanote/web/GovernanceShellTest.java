package com.datanote.web;

import com.datanote.common.model.R;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理中心已原生整合进工作台(workspace.html)：header tab「数据治理」→ ops-sidebar 8 模块,
 * 各 gov-*.js 自注册到 GOV_RENDERERS,switchGovModule 渲染到内容区。独立 governance.html 改为重定向。
 */
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
    void governanceIntegratedIntoWorkspace() throws Exception {
        String ws = read("src/main/resources/static/workspace.html");
        assertTrue(ws.contains("js/dn-common.js"), "工作台应加载治理公共层");
        assertTrue(ws.contains("id=\"govSidebar\""), "应有原生 ops-sidebar 治理菜单容器");
        assertTrue(ws.contains("function initGovCenter"), "应有 initGovCenter 构建侧边菜单");
        assertTrue(ws.contains("function switchGovModule"), "应有 switchGovModule 渲染模块");
        assertTrue(ws.contains("var GOV_MODS"), "应有治理模块清单");
        assertTrue(ws.contains("数据标准") && ws.contains("分类分级")
                        && ws.contains("治理健康分") && ws.contains("审计中心"),
                "ops-sidebar 应含各治理模块");
    }

    @Test
    void workspaceRoutesGovernanceAndQuality() throws Exception {
        String ws = read("src/main/resources/static/workspace.html");
        assertTrue(ws.contains("'governance':  { view: 'viewGovernance'"),
                "#/governance 应指向治理视图");
        assertTrue(ws.contains("initGovCenter();"), "数据治理路由 init 应构建治理中心");
        assertTrue(ws.contains("'quality':"), "应保留 #/quality 路由");
    }

    @Test
    void governanceHtmlRedirectsToWorkspace() throws Exception {
        String gov = read("src/main/resources/static/governance.html");
        assertTrue(gov.contains("workspace.html#/governance"), "独立页应重定向到工作台治理入口");
        assertTrue(gov.contains("location.replace"), "应用 location.replace 重定向");
    }
}
