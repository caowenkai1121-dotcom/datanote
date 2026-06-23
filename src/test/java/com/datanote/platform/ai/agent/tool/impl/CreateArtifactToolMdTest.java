package com.datanote.platform.ai.agent.tool.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CreateArtifactTool.mdToHtml 服务端 markdown 渲染单测: 锁定 XSS 防护与基础语法行为(R101/R117 修复回归保护)。 */
class CreateArtifactToolMdTest {

    @Test
    void blocksJavascriptLinkXss() {
        String h = CreateArtifactTool.mdToHtml("[恶意](javascript:alert(1))");
        assertFalse(h.toLowerCase().contains("href=\"javascript"), "javascript: 链接不得渲染为 href");
        assertTrue(h.contains("恶意"), "链接文本应保留");
    }

    @Test
    void escapesQuoteInHref() {
        String h = CreateArtifactTool.mdToHtml("[x](http://a.com/\"onx=1)");
        assertFalse(h.contains("\"onx=1\""), "href 内引号须转义, 防属性逃逸");
    }

    @Test
    void normalLinkRendered() {
        String h = CreateArtifactTool.mdToHtml("[官网](https://a.com)");
        assertTrue(h.contains("href=\"https://a.com\""));
    }

    @Test
    void rendersTable() {
        String h = CreateArtifactTool.mdToHtml("| 维度 | 得分 |\n|---|---|\n| 质量 | 99 |");
        assertTrue(h.contains("<table>") && h.contains("<th>") && h.contains("<td>"), "应渲染表格");
    }

    @Test
    void unclosedFenceNotSwallowContent() {
        String h = CreateArtifactTool.mdToHtml("```\n孤立围栏\n后续正文");
        assertTrue(h.contains("后续正文"), "未闭合代码块不得吞掉后续内容");
    }

    @Test
    void headingAndList() {
        String h = CreateArtifactTool.mdToHtml("# 标题\n\n- a\n- b\n\n1) c");
        assertTrue(h.contains("<h1>") && h.contains("<ul>") && h.contains("<ol>"));
    }

    @Test
    void escapesRawHtml() {
        String h = CreateArtifactTool.mdToHtml("<script>x</script> 文本");
        assertFalse(h.contains("<script>"), "原始 HTML 须转义");
    }
}
