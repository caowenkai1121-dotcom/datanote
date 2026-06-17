package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.TaskSchedulerService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RunBackfillTool implements AiTool {

    private final TaskSchedulerService taskSchedulerService;

    @Override public String name() { return "run_backfill"; }
    @Override public String group() { return "ops"; }
    @Override public String description() {
        return "对指定根任务发起补数据(backfill), 指定补数日期(yyyy-MM-dd), 返回批次号 batchId, 由调度引擎异步执行。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"taskId\":{\"type\":\"number\",\"required\":true,\"desc\":\"根任务ID\"}," +
               "\"taskType\":{\"type\":\"string\",\"required\":true,\"desc\":\"根任务类型\"}," +
               "\"runDate\":{\"type\":\"string\",\"required\":true,\"desc\":\"补数日期, 格式 yyyy-MM-dd\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "operations:backfill"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long taskId = AgentArgs.longOrCtx(args, "taskId", ctx);
            if (taskId == null) return AiToolResult.fail("bad_arguments", "taskId 不能为空");
            String taskType = AgentArgs.strOrCtx(args, "taskType", ctx);
            if (taskType == null) return AiToolResult.fail("bad_arguments", "taskType 不能为空");
            String runDateStr = AgentArgs.str(args, "runDate");
            if (runDateStr == null) return AiToolResult.fail("bad_arguments", "runDate 不能为空");
            LocalDate runDate;
            try {
                runDate = LocalDate.parse(runDateStr);
            } catch (DateTimeParseException e) {
                return AiToolResult.fail("bad_arguments", "runDate 须为 yyyy-MM-dd");
            }
            Map<String, Object> result = taskSchedulerService.startBackfill(taskId, taskType, runDate, null);
            return AiToolResult.ok(result);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
