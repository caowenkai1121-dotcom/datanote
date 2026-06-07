package com.datanote.domain.governance;

import com.datanote.domain.governance.IssueService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单运营纯函数单测（R4）：SLA 阈值 / 超期判定 / CSV 防注入。
 */
class IssueOpsTest {

    @Test
    void slaThresholdsBySeverity() {
        assertEquals(24L, IssueService.slaHours("HIGH"));
        assertEquals(72L, IssueService.slaHours("MEDIUM"));
        assertEquals(168L, IssueService.slaHours("LOW"));
        assertEquals(72L, IssueService.slaHours(null), "未知级别按 MEDIUM");
    }

    @Test
    void overdueOnlyForUnresolvedBeyondSla() {
        assertTrue(IssueService.isOverdue("OPEN", "HIGH", 25), "HIGH 超24h 超期");
        assertFalse(IssueService.isOverdue("OPEN", "HIGH", 23), "未到阈值不超期");
        assertTrue(IssueService.isOverdue("FIXING", "MEDIUM", 80), "FIXING 也计 SLA");
        assertFalse(IssueService.isOverdue("CLOSED", "HIGH", 999), "已关不超期");
        assertFalse(IssueService.isOverdue("RESOLVED", "HIGH", 999), "已解决不计超期");
        assertFalse(IssueService.isOverdue("VERIFIED", "HIGH", 999));
        assertTrue(IssueService.isOverdue("LOW".equals("x") ? "OPEN" : "OPEN", "LOW", 168), "LOW 达168h 超期");
    }

    @Test
    void csvInjectionGuarded() {
        assertEquals("'=SUM(A1)", IssueService.csvCell("=SUM(A1)"), "= 开头加前缀单引号");
        assertEquals("'+1", IssueService.csvCell("+1"));
        assertEquals("'-1", IssueService.csvCell("-1"));
        assertEquals("'@x", IssueService.csvCell("@x"));
        assertEquals("\"a,b\"", IssueService.csvCell("a,b"), "含逗号加引号");
        assertEquals("\"a\"\"b\"", IssueService.csvCell("a\"b"), "引号转义");
        assertEquals("normal", IssueService.csvCell("normal"));
        assertEquals("", IssueService.csvCell(null));
    }
}
