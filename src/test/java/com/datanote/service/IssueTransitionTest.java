package com.datanote.service;

import com.datanote.domain.governance.IssueService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单状态机纯函数单测 —— OPEN→FIXING→RESOLVED→VERIFIED→CLOSED 闭环合法/非法流转。
 */
class IssueTransitionTest {

    @Test
    void legalForwardFlow() {
        assertTrue(IssueService.isLegalTransition("OPEN", "FIXING"));
        assertTrue(IssueService.isLegalTransition("FIXING", "RESOLVED"));
        assertTrue(IssueService.isLegalTransition("RESOLVED", "VERIFIED"));
        assertTrue(IssueService.isLegalTransition("VERIFIED", "CLOSED"));
    }

    @Test
    void legalBackwardAndDirectClose() {
        assertTrue(IssueService.isLegalTransition("OPEN", "CLOSED"), "可直接关单");
        assertTrue(IssueService.isLegalTransition("FIXING", "OPEN"), "可退回");
        assertTrue(IssueService.isLegalTransition("RESOLVED", "FIXING"), "复检不通过退回");
        assertTrue(IssueService.isLegalTransition("VERIFIED", "FIXING"));
        assertTrue(IssueService.isLegalTransition("CLOSED", "OPEN"), "可重开");
    }

    @Test
    void illegalJumps() {
        assertFalse(IssueService.isLegalTransition("OPEN", "RESOLVED"), "不可跳过整改");
        assertFalse(IssueService.isLegalTransition("OPEN", "VERIFIED"));
        assertFalse(IssueService.isLegalTransition("FIXING", "VERIFIED"), "未提交整改不可直接复检通过");
        assertFalse(IssueService.isLegalTransition("RESOLVED", "CLOSED"), "须先复检");
    }

    @Test
    void unknownOrSameStatusRejected() {
        assertFalse(IssueService.isLegalTransition("OPEN", "OPEN"), "同态非流转");
        assertFalse(IssueService.isLegalTransition("FOO", "OPEN"), "未知源态");
        assertFalse(IssueService.isLegalTransition("OPEN", "BAR"), "未知目标态");
        assertFalse(IssueService.isLegalTransition(null, "OPEN"));
    }
}
