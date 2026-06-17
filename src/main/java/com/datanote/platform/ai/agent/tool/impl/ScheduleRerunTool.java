package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.TaskExecutionService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ScheduleRerunTool implements AiTool {

    private final TaskExecutionService taskExecutionService;

    @Override public String name() { return "schedule_rerun"; }
    @Override public String group() { return "ops"; }
    @Override public String description() {
        return "重跑(retry)一个调度运行实例(按 runId), 用于失败任务重试。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"runId\":{\"type\":\"number\",\"required\":true,\"desc\":\"调度运行实例ID\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "operations:schedule"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long runId = AgentArgs.longOrCtx(args, "runId", ctx);
            if (runId == null) return AiToolResult.fail("bad_arguments", "runId 不能为空");
            taskExecutionService.retryTask(runId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("note", "已发起重跑");
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
