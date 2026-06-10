package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.engine.ChildAgentRunner;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * delegate_task：把 1~4 个【只读探查子任务】分派给子代理【并行】执行(分治, 天工开物·工具链编排)。
 * 各子在隔离会话用只读工具独立完成, 只回小结给父汇总。适合需并行调研多个对象再综合的场景
 * (如同时分析多张表/多条血缘/多个指标), 比父串行逐个查更快。
 * 子代理不能写业务数据、不能再委派(防递归, 深度=1)、不能反向求助 —— 写动作仍由父层走审批。
 */
@Component
@RequiredArgsConstructor
public class DelegateTaskTool implements AiTool {

    private final ChildAgentRunner childRunner;

    private static final int MAX_TASKS = 4;

    @Override public String name() { return "delegate_task"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "把 1~4 个【只读探查子任务】分派给子代理并行执行, 各子独立用只读工具完成并回小结, 适合需并行调研多对象再综合的场景。"
                + "参数 tasks 为数组, 每项 {goal:子任务目标(一句话, 自包含)}; 也可直接传字符串数组。"
                + "子代理只读、不可写/不可再委派。请只在确有多个可并行的独立子任务时使用。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"tasks\":{\"type\":\"array\",\"required\":true,\"desc\":\"[{goal}] 或 [字符串], 1~4 个独立只读子任务\"}}";
    }
    @Override public boolean readOnly() { return true; } // 子全只读→无写副作用, 不触审批
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        JsonNode tasks = args == null ? null : args.get("tasks");
        if (tasks == null || !tasks.isArray() || tasks.size() == 0) {
            return AiToolResult.fail("bad_arguments", "tasks 需为非空数组(1~4 个子任务)");
        }
        List<String> goals = new ArrayList<>();
        for (JsonNode t : tasks) {
            String g = t.isTextual() ? t.asText() : t.path("goal").asText(null);
            if (g != null && !g.trim().isEmpty()) goals.add(g.trim());
            if (goals.size() >= MAX_TASKS) break;
        }
        if (goals.isEmpty()) return AiToolResult.fail("bad_arguments", "未解析到有效子任务 goal");

        String parentSession = ctx == null ? null : ctx.getSessionId();
        List<ChildAgentRunner.ChildResult> results = childRunner.runBatch(goals, ctx, parentSession);

        List<Map<String, Object>> out = new ArrayList<>();
        for (ChildAgentRunner.ChildResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal", r.goal);
            m.put("status", r.status);
            m.put("summary", r.summary);
            m.put("childSessionId", r.childSessionId);
            out.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("delegated", goals.size());
        data.put("results", out);
        return AiToolResult.ok(data);
    }
}
