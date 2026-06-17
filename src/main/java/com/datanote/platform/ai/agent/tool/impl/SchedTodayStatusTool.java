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
import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：今日(或指定日)调度任务成败汇总。封装 TaskSchedulerService.getTodayStatus。值班巡检。带 _deeplink。 */
@Component
@RequiredArgsConstructor
public class SchedTodayStatusTool implements AiTool {

    private final TaskSchedulerService taskSchedulerService;

    @Override public String name() { return "sched_today_status"; }
    @Override public String group() { return "scheduler"; }
    @Override public String description() {
        return "查今日(或指定日)调度任务运行的成败汇总(成功/失败/运行中/未跑)。早晨值班巡检全局调度健康。参数 date 可选(yyyy-MM-dd, 默认今天)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"date\":{\"type\":\"string\",\"required\":false,\"desc\":\"yyyy-MM-dd, 默认今天\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "operations:schedule"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String dateStr = AgentArgs.strOrCtx(args, "date", ctx);
            LocalDate date;
            try { date = (dateStr == null) ? LocalDate.now() : LocalDate.parse(dateStr); }
            catch (Exception e) { date = LocalDate.now(); }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", date.toString());
            out.put("status", taskSchedulerService.getTodayStatus(date));
            out.put("_deeplink", AgentArgs.link("operations", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
