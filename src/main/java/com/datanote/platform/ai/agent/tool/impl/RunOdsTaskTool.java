package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写工具(HIGH): 运行【数据开发 → ODS 层】已存在的 ODS 同步任务(DnSyncTask), 把源表数据拉入 Doris ods 表。
 * 与 run_sync_job(数据同步模块 DnSyncJob, ID 体系不互通)不同 —— ODS 任务由 create_ods_table 创建, 属数据开发模块, 按 taskId 运行。
 * 复用 TaskExecutionService.runSyncTaskManually(与调度同款执行核心), 零重复。
 */
@Component
@RequiredArgsConstructor
public class RunOdsTaskTool implements AiTool {

    private final com.datanote.domain.orchestration.TaskExecutionService taskExecutionService;
    private final com.datanote.domain.integration.mapper.DnSyncTaskMapper syncTaskMapper;

    @Override public String name() { return "run_ods_task"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "【运行『数据开发 → ODS 层』的 ODS 同步任务, 把源表数据拉入 Doris 数仓】(写操作, 高风险, 需审批): "
                + "对 create_ods_table 建好的 ODS 任务(DnSyncTask)按 taskId 触发同步(预检源库→确保Doris表→DataX抽数→ANALYZE)。"
                + "用户说『运行ODS任务/把ODS任务跑起来/拉数据到数仓』且任务在数据开发ODS层时用本工具; "
                + "不要用 run_sync_job(那是『数据同步』模块的 DnSyncJob, 与 ODS 任务ID 体系不互通)。"
                + "参数 taskId 必填(create_ods_table 返回的 taskId)。同步可能耗时, 完成返回成功/失败与日志。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"taskId\":{\"type\":\"number\",\"required\":true,\"desc\":\"ODS 同步任务ID(create_ods_table 返回的 taskId)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }
    @Override public String requiredPerm() { return "develop:run"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long taskId = AgentArgs.longVal(args, "taskId");
            if (taskId == null) return AiToolResult.fail("bad_arguments", "taskId 不能为空");
            com.datanote.domain.integration.model.DnSyncTask task = syncTaskMapper.selectById(taskId);
            if (task == null) {
                return AiToolResult.fail("not_found",
                        "ODS 同步任务不存在: " + taskId + "(请确认是『数据开发 ODS 层』的任务ID; 若是『数据同步』模块任务请改用 run_sync_job)");
            }
            String user = ctx != null ? ctx.getUserName() : null;
            com.datanote.domain.orchestration.model.DnTaskExecution exec = taskExecutionService.runSyncTaskManually(taskId, user);
            boolean ok = "SUCCESS".equals(exec.getStatus());
            String odsTable = "ods." + task.getTargetTable();
            if (!ok) {
                String lg = exec.getLog();
                String tail = lg != null && lg.length() > 800 ? lg.substring(lg.length() - 800) : (lg == null ? "" : lg);
                return AiToolResult.fail("sync_failed", "ODS 任务 " + taskId + " 同步失败。日志末段: " + tail);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", taskId);
            out.put("taskName", task.getTaskName());
            out.put("odsTable", odsTable);
            out.put("status", exec.getStatus());
            out.put("durationSec", exec.getDuration());
            out.put("success", true);
            out.put("location", "数据开发 → ODS 层");
            out.put("note", "已运行 ODS 任务, 源表数据已拉入 " + odsTable);
            out.put("_deeplink", AgentArgs.openTableLink(task.getTargetDb() == null ? "ods" : task.getTargetDb(), task.getTargetTable()));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
