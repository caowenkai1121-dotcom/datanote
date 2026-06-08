package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.TaskSchedulerService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：调度某次运行的日志全文。封装 TaskSchedulerService.getRunLog。定位任务失败根因。带 _deeplink 回数据运维。 */
@Component
@RequiredArgsConstructor
public class SchedRunLogTool implements AiTool {

    private final TaskSchedulerService taskSchedulerService;

    @Override public String name() { return "sched_run_log"; }
    @Override public String group() { return "scheduler"; }
    @Override public String description() {
        return "查调度某次运行(runId)的执行日志全文。调度任务失败时定位根因(异常栈/SQL报错/超时)。参数 runId 必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"runId\":{\"type\":\"number\",\"required\":true,\"desc\":\"调度运行ID\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long runId = AgentArgs.longOrCtx(args, "runId", ctx);
            if (runId == null) return AiToolResult.fail("bad_arguments", "runId 不能为空");
            String log = taskSchedulerService.getRunLog(runId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runId", runId);
            out.put("log", log == null ? "" : (log.length() > 8000 ? log.substring(0, 8000) + "…(截断)" : log));
            out.put("_deeplink", AgentArgs.link("operations", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
