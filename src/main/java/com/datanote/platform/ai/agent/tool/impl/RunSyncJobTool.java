package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.integration.service.SyncJobExecutor;
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
public class RunSyncJobTool implements AiTool {

    private final SyncJobExecutor syncJobExecutor;

    @Override public String name() { return "run_sync_job"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "运行(触发)一个已存在的【数据同步】模块任务(DnSyncJob), 异步执行, 返回执行ID(execId); 用 sync_job_detail/sync_job_checkpoints 查进度。写操作需审批。"
                + "注意: 仅用于『数据同步』模块的 job; 若要运行『数据开发 → ODS 层』的 ODS 任务(create_ods_table 建的), 请改用 run_ods_task(ID 体系不互通)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"jobId\":{\"type\":\"number\",\"required\":true,\"desc\":\"数据同步任务ID\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "dbsync:run"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long jobId = AgentArgs.longOrCtx(args, "jobId", ctx);
            if (jobId == null) return AiToolResult.fail("bad_arguments", "jobId 不能为空");
            Long execId = syncJobExecutor.run(jobId, "AGENT");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("execId", execId);
            out.put("note", "已触发异步执行, 用 sync_job_detail 查进度");
            return AiToolResult.ok(out);
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
