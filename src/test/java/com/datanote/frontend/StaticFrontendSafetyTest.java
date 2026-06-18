package com.datanote.frontend;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFrontendSafetyTest {

    private static String read(String path) throws Exception {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void loginErrorMessage_shouldNotInjectServerTextAsHtml() throws Exception {
        String html = read("src/main/resources/static/login.html");

        assertFalse(html.contains("errEl.innerHTML = ICON_WARN + '<span>' + (msg"),
                "登录失败消息不能把服务端返回文本拼进 innerHTML");
        assertTrue(html.contains("messageNode.textContent = msg ||"),
                "登录失败消息应通过 textContent 写入文本节点");
    }

    @Test
    void scriptOnlineToolbar_shouldUseDeveloperEditPermissionForApprovalSubmit() throws Exception {
        String html = read("src/main/resources/static/workspace.html");

        assertTrue(html.contains("id=\"toolbarOnlineBtn\" data-perm=\"develop:edit\""),
                "脚本提交上线/下线申请应由 develop:edit 控制，而不是 operations:schedule");
    }

    @Test
    void legacyMessageBox_shouldRenderMessageAsText() throws Exception {
        String html = read("src/main/resources/static/workspace.html");

        assertFalse(html.contains("document.getElementById('msgBoxMsg').innerHTML = msg || '';"),
                "通用消息框正文不能把 msg 当 HTML 渲染");
        assertTrue(html.contains("document.getElementById('msgBoxMsg').textContent = msg || '';"),
                "通用消息框正文应通过 textContent 渲染");
    }

    @Test
    void commandPaletteWriteActions_shouldDeclareAndEnforcePermissions() throws Exception {
        String html = read("src/main/resources/static/workspace.html");

        assertTrue(html.contains("function dnCmdKCanRun(it)"),
                "命令面板应有统一权限判断");
        assertTrue(html.contains("_cmdkItems.filter(dnCmdKCanRun)"),
                "命令面板列表应过滤无权限命令");
        assertTrue(html.contains("kw: 'new script xjjb develop sql kaifa', perm: 'develop:edit'"),
                "新建脚本命令应声明 develop:edit");
        assertTrue(html.contains("kw: 'new metric xjzb add zhibiao', perm: 'metrics:edit'"),
                "新建指标命令应声明 metrics:edit");
        assertTrue(html.contains("kw: 'new task integration xjrw etl jicheng', perm: 'dbsync:edit'"),
                "新建集成任务命令应声明 dbsync:edit");
    }

    @Test
    void homeQuickActions_shouldDeclareButtonPermissions() throws Exception {
        String js = read("src/main/resources/static/js/home-dashboard.js");

        assertTrue(js.contains("var qa = function (label, perm, fn)"),
                "首页快捷入口应支持权限参数");
        assertTrue(js.contains("if (perm) attrs['data-perm'] = perm;"),
                "首页快捷入口应把权限点写到 data-perm");
        assertTrue(js.contains("qa('＋ 新建脚本', 'develop:edit'"),
                "首页新建脚本快捷入口应受 develop:edit 控制");
        assertTrue(js.contains("qa('＋ 新建指标', 'metrics:edit'"),
                "首页新建指标快捷入口应受 metrics:edit 控制");
        assertTrue(js.contains("qa('＋ 集成任务', 'dbsync:edit'"),
                "首页新建集成任务快捷入口应受 dbsync:edit 控制");
    }

    @Test
    void mdmQualityEmptyState_shouldUsePlainOperationalText() throws Exception {
        String js = read("src/main/resources/static/js/mdm-quality.js");

        assertFalse(js.contains("🎉"), "后台空态文案不应使用庆祝表情");
        assertTrue(js.contains("全部 ' + records.length + ' 条记录均合规'"),
                "合规空态应保留清晰的业务反馈");
    }
}
