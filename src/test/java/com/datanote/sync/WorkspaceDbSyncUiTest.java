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
        String html = readWorkspaceHtml();

        assertTrue(html.contains(".dbsync-action-btns .btn"),
                "task action buttons should have dedicated compact sizing");
        assertTrue(html.contains("dbsync-action-btns"),
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
    void routeInitializerUsesWindowScopedDbSyncFunctions() throws Exception {
        String html = readWorkspaceHtml();

        assertTrue(html.contains("window.dbsyncLoadFolders = function"),
                "dbsync folder loader should be available to the route initializer");
        assertTrue(html.contains("window.dbsyncLoadFolders().then(function() { window.dbsyncLoadJobs(); }); window.dbsyncRefreshLogFilter();"),
                "dbsync route init should call window-scoped functions after refresh navigation");
    }

    private static String readWorkspaceHtml() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("src/main/resources/static/workspace.html"));
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
