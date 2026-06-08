package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.integration.service.SyncJobService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 只读：同步任务基础信息(配置/源目标/状态)。封装 SyncJobService.getById。带 _deeplink 回数据同步。 */
@Component
@RequiredArgsConstructor
public class SyncJobDetailTool implements AiTool {

    private final SyncJobService syncJobService;

    @Override public String name() { return "sync_job_detail"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "查同步任务的基础信息(配置/源库目标库/同步模式/调度状态)。同步排障第一步。参数 jobId 必填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"jobId\":{\"type\":\"number\",\"required\":true,\"desc\":\"同步任务ID\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long jobId = AgentArgs.longOrCtx(args, "jobId", ctx);
            if (jobId == null) return AiToolResult.fail("bad_arguments", "jobId 不能为空");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("job", syncJobService.getById(jobId));
            out.put("_deeplink", AgentArgs.dbsyncLink(jobId));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
