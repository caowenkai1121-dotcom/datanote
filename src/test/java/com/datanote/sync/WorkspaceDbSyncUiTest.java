package com.datanote.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceDbSyncUiTest {

    @Test
    void folderNavigationIsNestedUnderTaskListInSidebar() throws Exception {
        String html = readWorkspaceHtml();

        int jobsNav = html.indexOf("id=\"dbsyncJobsNav\"");
        int folderNav = html.indexOf("id=\"dbsyncFolderNav\"");
        int logsNav = html.indexOf("id=\"dbsyncLogsNav\"");

        assertTrue(jobsNav >= 0, "dbsync task list nav should have a stable id");
        assertTrue(folderNav > jobsNav, "folder navigation should be rendered below task list");
        assertTrue(logsNav > folderNav, "folder navigation should remain above realtime logs");
        assertFalse(html.contains("id=\"dbsyncFolderBar\""), "folder tabs should not stay above the table");
    }

    @Test
    void taskActionButtonsUseCompactLayoutRules() throws Exception {
        // CSS 已从 workspace.html 内联 <style> 外置到 css/app.css(R6 前端重构)
        assertTrue(readAppCss().contains(".dbsync-action-btns .btn"),
                "task action buttons should have dedicated compact sizing");
        assertTrue(readWorkspaceHtml().contains("dbsync-action-btns"),
                "task rows should still render action buttons inside the compact container");
    }

    @Test
    void folderCrudUsesCustomModalWithNestedCreateAndRenameActions() throws Exception {
        String html = readWorkspaceHtml();

        assertFalse(html.contains("window.prompt("), "folder create/rename should not use browser prompt");
        assertTrue(html.contains("id=\"dbsyncFolderModal\""), "folder create/rename should use the app modal");
        assertTrue(html.contains("id=\"dbsyncFolderNameInput\""), "folder modal should include a name input");
        assertTrue(html.contains("id=\"dbsyncFolderParentId\""), "folder modal should carry the parent folder id");
        assertTrue(html.contains("dbsyncOpenCreateChildFolder"), "folders should support creating child folders");
        assertTrue(html.contains("dbsyncOpenRenameFolder"), "folders should support rename action");
    }

    @Test
    void pipelineProcessingControlsArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncPreSql\""),
                "advanced section should have a preSql textarea");
        assertTrue(html.contains("id=\"dbsyncPostSql\""),
                "advanced section should have a postSql textarea");
        assertTrue(html.contains("transformExpression"),
                "field mapping rows should support transformExpression input");
        assertTrue(html.contains("dfs-filter-expr"),
                "table rows should include a filterExpression input");
    }

    @Test
    void robustnessControlsArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncErrorLimitRows\""),
                "advanced section should have errorLimitRows input");
        assertTrue(html.contains("id=\"dbsyncErrorLimitRatio\""),
                "advanced section should have errorLimitRatio input");
        assertTrue(html.contains("id=\"dbsyncRetryTimes\""),
                "advanced section should have retryTimes input");
        assertTrue(html.contains("id=\"dbsyncRetryBackoffType\""),
                "advanced section should have retryBackoffType select");
        assertTrue(html.contains("id=\"dbsyncRetryBackoffDelay\""),
                "advanced section should have retryBackoffDelay input");
        assertTrue(html.contains("id=\"dbsyncRateLimitMode\""),
                "advanced section should have rateLimitMode select");
        assertTrue(html.contains("id=\"dbsyncRateLimitValue\""),
                "advanced section should have rateLimitValue input");
    }

    @Test
    void routeInitializerUsesWindowScopedDbSyncFunctions() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("window.dbsyncLoadFolders = function"),
                "dbsync folder loader should be available to the route initializer");
        assertTrue(html.contains("window.dbsyncLoadFolders().then(function() { window.dbsyncLoadJobs(); }); window.dbsyncRefreshLogFilter();"),
                "dbsync route init should call window-scoped functions after refresh navigation");
    }

    @Test
    void cdcMetricsPanelContainsExtendedFields() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("eventsSeen"),
                "CDC metrics panel should display eventsSeen field");
        assertTrue(html.contains("snapshotRunning"),
                "CDC metrics panel should display snapshotRunning field");
        assertTrue(html.contains("snapshotCompleted"),
                "CDC metrics panel should display snapshotCompleted field");
        assertTrue(html.contains("dbsyncCdcReset"),
                "CDC action area should contain a reset function call");
        assertTrue(html.contains("/api/cdc/' + id + '/reset?confirm=true"),
                "CDC reset should call the reset endpoint with confirm=true");
    }

    @Test
    void auditHistoryTabAndApiCallArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("/audit"),
                "detail drawer should call /audit endpoint for operation history");
        assertTrue(html.contains("操作审计"),
                "detail drawer should have an audit tab labeled '操作审计'");
        assertTrue(html.contains("dbsyncRenderAudit"),
                "should have a dbsyncRenderAudit function to render audit records");
        assertTrue(html.contains("dbsyncDetailPane_audit"),
                "detail drawer should have an audit pane element");
    }

    @Test
    void dashboardModalAndPollingArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncDashboardModal\""),
                "dashboard modal overlay should have a stable id");
        assertTrue(html.contains("id=\"dbsyncDashboardBody\""),
                "dashboard modal should have a tbody element for rows");
        assertTrue(html.contains("/api/sync-job/dashboard"),
                "dashboard fetch should call /api/sync-job/dashboard");
        assertTrue(html.contains("dbsyncOpenDashboard"),
                "toolbar should reference dbsyncOpenDashboard function");
        assertTrue(html.contains("dbsyncCloseDashboard"),
                "dashboard close should call dbsyncCloseDashboard");
        assertTrue(html.contains("_dbsyncDashboardTimer"),
                "dashboard polling timer variable should be managed");
        assertTrue(html.contains("clearInterval(_dbsyncDashboardTimer)"),
                "dashboard close should clearInterval to prevent timer leak");
        assertTrue(html.contains("setInterval(dbsyncFetchDashboard, 5000)"),
                "dashboard should poll every 5 seconds via setInterval");
    }

    @Test
    void reconcileButtonAndModalArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncReconcileModal\""),
                "reconcile modal overlay should have a stable id");
        assertTrue(html.contains("id=\"dbsyncReconcileBody\""),
                "reconcile modal should have a tbody element for results");
        assertTrue(html.contains("/reconcile"),
                "reconcile should call the /reconcile endpoint");
        assertTrue(html.contains("dbsyncOpenReconcile"),
                "row menu and drawer should reference dbsyncOpenReconcile function");
        assertTrue(html.contains("dbsyncCloseReconcile"),
                "reconcile modal should have a close function");
    }

    @Test
    void checksumButtonAndModalArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncChecksumModal\""),
                "checksum modal overlay should have a stable id");
        assertTrue(html.contains("id=\"dbsyncChecksumBody\""),
                "checksum modal should have a body element for results");
        assertTrue(html.contains("/checksum"),
                "checksum should call the /checksum endpoint");
        assertTrue(html.contains("dbsyncOpenChecksum"),
                "row menu and drawer should reference dbsyncOpenChecksum function");
        assertTrue(html.contains("dbsyncCloseChecksum"),
                "checksum modal should have a close function");
        assertTrue(html.contains("mismatchBuckets"),
                "checksum result rendering should handle mismatchBuckets detail rows");
    }

    @Test
    void incrementalSnapshotAndDdlSyncControlsArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncIncrSnapshot\""),
                "advanced section should have incrementalSnapshot select");
        assertTrue(html.contains("id=\"dbsyncDdlSync\""),
                "advanced section should have ddlSync select");
        assertTrue(html.contains("incrementalSnapshotEnabled"),
                "save payload should include incrementalSnapshotEnabled field");
        assertTrue(html.contains("ddlSyncEnabled"),
                "save payload should include ddlSyncEnabled field");
        assertTrue(html.contains("dbsyncTriggerIncrSnapshot"),
                "CDC action area should contain dbsyncTriggerIncrSnapshot function");
        assertTrue(html.contains("/api/cdc/' + id + '/incremental-snapshot"),
                "incremental snapshot should call the /incremental-snapshot endpoint");
    }

    @Test
    void priorityAndDependencyControlsArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("id=\"dbsyncPriority\""),
                "advanced section should have a priority input");
        assertTrue(html.contains("priority:"),
                "save payload should include priority field");
        assertTrue(html.contains("/dependencies"),
                "detail drawer should call /dependencies endpoint");
        assertTrue(html.contains("dbsyncLoadDeps"),
                "should have a dbsyncLoadDeps function");
        assertTrue(html.contains("dbsyncAddDep"),
                "should have a dbsyncAddDep function");
        assertTrue(html.contains("dbsyncRemoveDep"),
                "should have a dbsyncRemoveDep function");
        assertTrue(html.contains("id=\"dbsyncDepsList\""),
                "dependency list container should have a stable id");
        assertTrue(html.contains("id=\"dbsyncDepSelect\""),
                "dependency upstream selector should have a stable id");
    }

    @Test
    void checkpointTabAndResetActionsArePresent() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("data-tab=\"checkpoint\""),
                "detail drawer should have a checkpoint tab button");
        assertTrue(html.contains("id=\"dbsyncDetailPane_checkpoint\""),
                "detail drawer should have a checkpoint pane element");
        assertTrue(html.contains("/checkpoints"),
                "checkpoint tab should load /api/sync-job/{id}/checkpoints");
        assertTrue(html.contains("/checkpoint/reset-incremental"),
                "checkpoint panel should call reset-incremental endpoint");
        assertTrue(html.contains("/checkpoint/reset-chunk"),
                "checkpoint panel should call reset-chunk endpoint");
        assertTrue(html.contains("dbsyncResetIncrementalCp"),
                "incremental checkpoint reset function should be present");
        assertTrue(html.contains("dbsyncResetChunkCp"),
                "chunk checkpoint reset function should be present");
    }

    private static String readWorkspaceHtml() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("src/main/resources/static/workspace.html"));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readAppCss() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("src/main/resources/static/css/app.css"));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
