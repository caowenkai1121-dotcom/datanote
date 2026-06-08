package com.datanote.platform.ai.agent.engine;

import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.RiskLevel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 写操作护栏(纯静态可单测)。三态判定只认 tool.readOnly()/tool.risk()/DENY_NAMES 三个不可变源，
 * 绝不接受 args/memory/config 任何外部输入(防被模型或记忆绕过)。
 */
public final class Guardrail {

    private Guardrail() {}

    public enum Gate { PASS, DENY, NEED_APPROVAL }

    /** 永久禁区二道防线(注册层本就不存在这些工具, 黑名单兜底防误注册)。 */
    private static final Set<String> DENY_NAMES = new HashSet<>(Arrays.asList(
            "drop_table", "delete_table", "batch_delete", "truncate_table",
            "grant_permission", "revoke_permission", "save_credential", "update_credential",
            "deploy", "save_guardrail_config", "execute_due_drops", "mark_for_drop", "delete_policy"));

    /** 三态门: 红线→DENY; 只读→PASS; 写→NEED_APPROVAL。 */
    public static Gate gate(AiTool tool) {
        if (tool == null || tool.name() == null) return Gate.DENY;
        if (DENY_NAMES.contains(tool.name())) return Gate.DENY;
        if (tool.readOnly()) return Gate.PASS;
        return Gate.NEED_APPROVAL;
    }

    /** HIGH(或 risk 缺失 fail-safe)每次强审批; 否则 MEDIUM 逐步确认(会话级一次性豁免)。 */
    public static boolean isHigh(AiTool tool) {
        return tool == null || tool.risk() == null || tool.risk() == RiskLevel.HIGH;
    }

    public static boolean isDenied(String name) {
        return name != null && DENY_NAMES.contains(name);
    }
}
