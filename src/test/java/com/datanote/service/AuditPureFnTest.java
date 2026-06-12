package com.datanote.service;

import com.datanote.platform.audit.AuditService;
import com.datanote.platform.audit.model.DnAuditLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12 审计纯函数单测（不依赖 Spring 上下文 / 数据库）：
 * shouldAudit 命中判定、classify 类型归类、csvCell 转义、toCsv 拼接。
 */
class AuditPureFnTest {

    // ---------- shouldAudit ----------

    @Test
    void postPutDeleteUnderApiAreAudited() {
        assertTrue(AuditService.shouldAudit("POST", "/api/gov/standard/rules"));
        assertTrue(AuditService.shouldAudit("PUT", "/api/sync/job/1"));
        assertTrue(AuditService.shouldAudit("DELETE", "/api/gov/classification/rules/3"));
        // 方法大小写不敏感
        assertTrue(AuditService.shouldAudit("post", "/api/x"));
    }

    @Test
    void plainGetNotAudited() {
        assertFalse(AuditService.shouldAudit("GET", "/api/gov/standard/rules"));
        assertFalse(AuditService.shouldAudit("HEAD", "/api/x"));
        assertFalse(AuditService.shouldAudit("OPTIONS", "/api/x"));
    }

    @Test
    void sensitiveReadGetAudited() {
        // 批4审计专项: 导出/下载/预览/探查类只读操作留痕
        assertTrue(AuditService.shouldAudit("GET", "/api/metadata/preview"));
        assertTrue(AuditService.shouldAudit("GET", "/api/metadata/profile"));
        assertTrue(AuditService.shouldAudit("GET", "/api/gov/standard/export"));
        assertTrue(AuditService.shouldAudit("GET", "/api/file/download/3"));
    }

    @Test
    void nonApiPathNotAudited() {
        assertFalse(AuditService.shouldAudit("POST", "/governance.html"));
        assertFalse(AuditService.shouldAudit("POST", "/js/gov-audit.js"));
    }

    @Test
    void auditSelfWritePathExcludedToAvoidRecursion() {
        // 审计自身变更路径(login-record 等)不再记，避免递归；检索 GET 天然不记
        assertFalse(AuditService.shouldAudit("POST", "/api/gov/audit/search"));
        assertFalse(AuditService.shouldAudit("POST", "/api/gov/audit/login-record"));
        assertFalse(AuditService.shouldAudit("GET", "/api/gov/audit/search"));
        // 批4: 审计日志导出本身是高敏感动作，反而必须留痕
        assertTrue(AuditService.shouldAudit("GET", "/api/gov/audit/export"));
    }

    @Test
    void nullSafe() {
        assertFalse(AuditService.shouldAudit(null, "/api/x"));
        assertFalse(AuditService.shouldAudit("POST", null));
    }

    // ---------- classify ----------

    @Test
    void classifyLogin() {
        assertEquals("LOGIN", AuditService.classify("POST", "/api/auth/login"));
    }

    @Test
    void classifyExport() {
        assertEquals("EXPORT", AuditService.classify("GET", "/api/gov/standard/export"));
    }

    @Test
    void classifyDataPreview() {
        // preview 关键字优先于 metadata，预览归 DATA_PREVIEW 而非 META_CHANGE
        assertEquals("DATA_PREVIEW", AuditService.classify("GET", "/api/metadata/preview"));
        assertEquals("DATA_PREVIEW", AuditService.classify("GET", "/api/metadata/profile"));
    }

    @Test
    void classifyPermChange() {
        assertEquals("PERM_CHANGE", AuditService.classify("POST", "/api/rbac/roles/1/perms"));
    }

    @Test
    void classifyMetaChange() {
        assertEquals("META_CHANGE", AuditService.classify("POST", "/api/gov/metadata/collect"));
        assertEquals("META_CHANGE", AuditService.classify("POST", "/api/gov/lineage/edge"));
    }

    @Test
    void classifyRuleChange() {
        assertEquals("RULE_CHANGE", AuditService.classify("POST", "/api/gov/quality/rules"));
        assertEquals("RULE_CHANGE", AuditService.classify("POST", "/api/gov/standard/rules"));
    }

    @Test
    void classifyLabelChange() {
        assertEquals("LABEL_CHANGE", AuditService.classify("POST", "/api/gov/classification/confirm"));
    }

    @Test
    void classifyFallbackDataAccess() {
        assertEquals("DATA_ACCESS", AuditService.classify("POST", "/api/sync/job/1/run"));
    }

    // ---------- csvCell 转义 ----------

    @Test
    void csvPlainCellUnquoted() {
        assertEquals("hello", AuditService.csvCell("hello"));
    }

    @Test
    void csvNullBecomesEmpty() {
        assertEquals("", AuditService.csvCell(null));
    }

    @Test
    void csvCommaQuoted() {
        assertEquals("\"a,b\"", AuditService.csvCell("a,b"));
    }

    @Test
    void csvQuoteDoubledAndWrapped() {
        // a"b -> "a""b"
        assertEquals("\"a\"\"b\"", AuditService.csvCell("a\"b"));
    }

    @Test
    void csvNewlineQuoted() {
        assertEquals("\"a\nb\"", AuditService.csvCell("a\nb"));
    }

    // ---------- toCsv 拼接 ----------

    @Test
    void toCsvHasHeaderAndRows() {
        List<DnAuditLog> rows = new ArrayList<>();
        DnAuditLog a = new DnAuditLog();
        a.setUserName("admin");
        a.setActionType("RULE_CHANGE");
        a.setMethod("POST");
        a.setPath("/api/gov/quality/rules");
        a.setIp("127.0.0.1");
        a.setStatus(200);
        a.setDetail("耗时12ms");
        rows.add(a);

        String csv = AuditService.toCsv(rows);
        String[] lines = csv.split("\n");
        // 第一行表头含中文列名
        assertTrue(lines[0].contains("操作人"));
        assertTrue(lines[0].contains("类型"));
        // 数据行包含字段值
        assertTrue(csv.contains("admin"));
        assertTrue(csv.contains("RULE_CHANGE"));
        assertTrue(csv.contains("/api/gov/quality/rules"));
    }

    @Test
    void toCsvEmptyListStillHasHeader() {
        String csv = AuditService.toCsv(new ArrayList<>());
        assertTrue(csv.contains("操作人"));
    }
}
