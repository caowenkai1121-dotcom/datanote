package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.TaskDependencyService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：调度任务下游依赖树。封装 TaskDependencyService.getDownstreamTree。评估失败爆炸半径。 */
@Component
@RequiredArgsConstructor
public class SchedDownstreamTreeTool implements AiTool {

    private final TaskDependencyService taskDependencyService;

    @Override public String name() { return "sched_downstream_tree"; }
    @Override public String group() { return "scheduler"; }
    @Override public String description() {
        return "查某调度任务的下游依赖树(会阻塞哪些下游任务)。任务失败时评估爆炸半径、决定补数顺序。参数 taskId 必填、taskType 必填(script/sync_task)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"taskId\":{\"type\":\"number\",\"required\":true,\"desc\":\"任务ID\"},\"taskType\":{\"type\":\"string\",\"required\":true,\"desc\":\"任务类型(script/sync_task)\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "operations:schedule"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long taskId = AgentArgs.longOrCtx(args, "taskId", ctx);
            String taskType = AgentArgs.strOrCtx(args, "taskType", ctx);
            if (taskId == null || taskType == null) return AiToolResult.fail("bad_arguments", "taskId 与 taskType 不能为空");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", taskId);
            out.put("taskType", taskType);
            out.put("downstreamTree", taskDependencyService.getDownstreamTree(taskId, taskType));
            out.put("_deeplink", AgentArgs.link("operations", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
