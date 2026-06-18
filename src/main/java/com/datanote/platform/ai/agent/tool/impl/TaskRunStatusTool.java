package com.datanote.platform.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
 * 只读工具: 查 ODS 同步任务 / 开发脚本的最近执行状态与日志(补 agent "能跑任务却看不到调度/历史执行结果"的缺口,
 * 与 run_ods_task/run_script 及 CRITIC 写后验证闭环)。按 scriptId 或 syncTaskId 查最近 N 次执行。
 */
@Component
@RequiredArgsConstructor
public class TaskRunStatusTool implements AiTool {

    private final com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper taskExecutionMapper;

    @Override public String name() { return "task_run_status"; }
    @Override public String group() { return "develop"; }
    @Override public String description() {
        return "查 ODS 同步任务/开发脚本的【最近执行状态与日志】(只读)。参数二选一: syncTaskId(ODS同步任务) 或 scriptId(开发脚本); "
                + "limit 默认3。返回每次执行的 status(RUNNING/SUCCESS/FAILED)/触发方式/耗时/起止时间/日志末段。"
                + "用于核实任务跑没跑成功、看失败原因、跟踪调度运行结果。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"syncTaskId\":{\"type\":\"number\",\"required\":false,\"desc\":\"ODS同步任务ID(与 scriptId 二选一)\"},"
                + "\"scriptId\":{\"type\":\"number\",\"required\":false,\"desc\":\"开发脚本ID(与 syncTaskId 二选一)\"},"
                + "\"limit\":{\"type\":\"number\",\"required\":false,\"desc\":\"返回最近几次, 默认3\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "develop:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long syncTaskId = AgentArgs.longVal(args, "syncTaskId");
            Long scriptId = AgentArgs.longVal(args, "scriptId");
            if (syncTaskId == null && scriptId == null) {
                return AiToolResult.fail("bad_arguments", "需提供 syncTaskId 或 scriptId 之一");
            }
            int limit = AgentArgs.intVal(args, "limit", 3);
            if (limit < 1) limit = 3;
            if (limit > 20) limit = 20;
            QueryWrapper<com.datanote.domain.orchestration.model.DnTaskExecution> qw = new QueryWrapper<>();
            if (syncTaskId != null) qw.eq("sync_task_id", syncTaskId);
            else qw.eq("script_id", scriptId);
            qw.orderByDesc("id").last("LIMIT " + limit);
            List<com.datanote.domain.orchestration.model.DnTaskExecution> rows = taskExecutionMapper.selectList(qw);
            List<Map<String, Object>> runs = new ArrayList<>();
            for (com.datanote.domain.orchestration.model.DnTaskExecution e : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("execId", e.getId());
                m.put("status", e.getStatus());
                m.put("triggerType", e.getTriggerType());
                m.put("durationSec", e.getDuration());
                m.put("startTime", e.getStartTime() == null ? null : e.getStartTime().toString());
                m.put("endTime", e.getEndTime() == null ? null : e.getEndTime().toString());
                String lg = e.getLog();
                m.put("logTail", lg == null ? null : (lg.length() > 600 ? lg.substring(lg.length() - 600) : lg));
                runs.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(syncTaskId != null ? "syncTaskId" : "scriptId", syncTaskId != null ? syncTaskId : scriptId);
            out.put("count", runs.size());
            out.put("runs", runs);
            if (runs.isEmpty()) out.put("note", "该任务/脚本暂无执行记录(可能尚未运行过)");
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
