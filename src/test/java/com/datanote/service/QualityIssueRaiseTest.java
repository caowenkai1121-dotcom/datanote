package com.datanote.service;

import com.datanote.model.DnQualityRule;
import com.datanote.model.DnQualityRun;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 质量→工单 自动联动纯函数单测：对象引用键 / 严重度判定 / 标题 / 描述。
 */
class QualityIssueRaiseTest {

    private DnQualityRule rule(String name, String severity, Integer block, BigDecimal threshold) {
        DnQualityRule r = new DnQualityRule();
        r.setId(7L);
        r.setRuleName(name);
        r.setRuleType("null_check");
        r.setDatabaseName("ods");
        r.setTableName("orders");
        r.setColumnName("amount");
        r.setSeverity(severity);
        r.setBlockDownstream(block);
        r.setPassThreshold(threshold);
        r.setDimension("完整性");
        return r;
    }

    private DnQualityRun run(String status, BigDecimal passRate) {
        DnQualityRun run = new DnQualityRun();
        run.setRuleId(7L);
        run.setRunStatus(status);
        run.setPassRate(passRate);
        run.setFailCount(3L);
        run.setTotalCount(100L);
        return run;
    }

    @Test
    void objectRefKeyedByRule() {
        assertEquals("qrule:7", IssueService.qualityIssueObjectRef(7L));
    }

    @Test
    void normalizeSeverityOnlyAcceptsThree() {
        assertEquals("HIGH", IssueService.normalizeSeverity(" high "));
        assertEquals("MEDIUM", IssueService.normalizeSeverity("Medium"));
        assertEquals("LOW", IssueService.normalizeSeverity("LOW"));
        assertNull(IssueService.normalizeSeverity("高"));
        assertNull(IssueService.normalizeSeverity(null));
    }

    @Test
    void errorRunIsHigh() {
        assertEquals("HIGH", IssueService.qualitySeverity(rule("r", null, 0, null), run("error", null)));
    }

    @Test
    void strongRuleEscalatesToHigh() {
        // 即使声明 LOW，强阻断规则失败应升 HIGH
        assertEquals("HIGH", IssueService.qualitySeverity(rule("r", "LOW", 1, BigDecimal.valueOf(99)), run("failed", BigDecimal.valueOf(98))));
    }

    @Test
    void declaredSeverityHonoredWhenNotStrong() {
        assertEquals("LOW", IssueService.qualitySeverity(rule("r", "low", 0, null), run("failed", BigDecimal.valueOf(10))));
    }

    @Test
    void deriveFromPassRateWhenNoDeclared() {
        assertEquals("HIGH", IssueService.qualitySeverity(rule("r", null, 0, null), run("failed", BigDecimal.valueOf(40))));
        assertEquals("MEDIUM", IssueService.qualitySeverity(rule("r", null, 0, null), run("failed", BigDecimal.valueOf(80))));
        assertEquals("LOW", IssueService.qualitySeverity(rule("r", null, 0, null), run("failed", BigDecimal.valueOf(95))));
        assertEquals("MEDIUM", IssueService.qualitySeverity(rule("r", null, 0, null), run("failed", null)));
    }

    @Test
    void titleFailedVsError() {
        String failed = IssueService.qualityIssueTitle(rule("订单金额非空", null, 0, BigDecimal.valueOf(95)), run("failed", BigDecimal.valueOf(80)));
        assertTrue(failed.contains("未达标"));
        assertTrue(failed.contains("订单金额非空"));
        assertTrue(failed.contains("80"));
        String err = IssueService.qualityIssueTitle(rule("订单金额非空", null, 0, null), run("error", null));
        assertTrue(err.contains("质量异常"));
        assertTrue(err.length() <= 200);
    }

    @Test
    void descriptionCarriesObjectAndSource() {
        String d = IssueService.qualityIssueDescription(rule("r", null, 0, BigDecimal.valueOf(95)), run("failed", BigDecimal.valueOf(80)));
        assertTrue(d.contains("ods.orders.amount"));
        assertTrue(d.contains("质量规则 #7"));
        assertTrue(d.contains("通过率"));
    }
}
