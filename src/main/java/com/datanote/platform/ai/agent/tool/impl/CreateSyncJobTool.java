package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.integration.model.DnSyncJob;
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

/** 写工具(MEDIUM): 新建数据同步任务。映射 UI 同款 SyncJobService.save(含服务端校验), 建成在数据同步模块列表可见。 */
@Component
@RequiredArgsConstructor
public class CreateSyncJobTool implements AiTool {

    private final SyncJobService syncJobService;

    @Override public String name() { return "create_sync_job"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "新建数据同步任务(写操作, 需人工审批)。建成后在数据同步模块列表可见, 可手动运行/定时调度。"
                + "参数 jobName、sourceDsId、targetDsId、syncMode(FULL/INCREMENTAL/CDC) 必填; "
                + "sourceDb/targetDb/writeMode(UPSERT/INSERT/INSERT_IGNORE)/scheduleCron/tableConfig(表配置JSON数组) 可选。"
                + "需先用只读工具确认数据源ID与库表存在。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"jobName\":{\"type\":\"string\",\"required\":true,\"desc\":\"任务名称\"},"
                + "\"sourceDsId\":{\"type\":\"number\",\"required\":true,\"desc\":\"源数据源ID\"},"
                + "\"targetDsId\":{\"type\":\"number\",\"required\":true,\"desc\":\"目标数据源ID\"},"
                + "\"syncMode\":{\"type\":\"string\",\"required\":true,\"desc\":\"FULL/INCREMENTAL/CDC\"},"
                + "\"sourceDb\":{\"type\":\"string\",\"required\":false},"
                + "\"targetDb\":{\"type\":\"string\",\"required\":false},"
                + "\"writeMode\":{\"type\":\"string\",\"required\":false,\"desc\":\"UPSERT/INSERT/INSERT_IGNORE\"},"
                + "\"scheduleCron\":{\"type\":\"string\",\"required\":false,\"desc\":\"Spring cron\"},"
                + "\"tableConfig\":{\"type\":\"string\",\"required\":false,\"desc\":\"表配置JSON数组,每项含sourceTable/targetTable\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String jobName = AgentArgs.str(args, "jobName");
            Long sourceDsId = AgentArgs.longVal(args, "sourceDsId");
            Long targetDsId = AgentArgs.longVal(args, "targetDsId");
            String syncMode = AgentArgs.str(args, "syncMode");
            if (jobName == null) return AiToolResult.fail("bad_arguments", "jobName 不能为空");
            if (sourceDsId == null || targetDsId == null) return AiToolResult.fail("bad_arguments", "sourceDsId/targetDsId 不能为空");
            if (syncMode == null) return AiToolResult.fail("bad_arguments", "syncMode 不能为空(FULL/INCREMENTAL/CDC)");
            DnSyncJob job = new DnSyncJob();
            job.setJobName(jobName);
            job.setSourceDsId(sourceDsId);
            job.setTargetDsId(targetDsId);
            job.setSyncMode(syncMode.toUpperCase());
            job.setSourceDb(AgentArgs.str(args, "sourceDb"));
            job.setTargetDb(AgentArgs.str(args, "targetDb"));
            job.setWriteMode(AgentArgs.str(args, "writeMode"));
            job.setScheduleCron(AgentArgs.str(args, "scheduleCron"));
            job.setTableConfig(AgentArgs.str(args, "tableConfig"));
            if (ctx != null && ctx.getUserName() != null) job.setCreatedBy(ctx.getUserName());
            DnSyncJob saved = syncJobService.save(job); // 内含必填/模式/cron/tableConfig 校验, 非法抛 IllegalArgumentException
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            out.put("_deeplink", AgentArgs.dbsyncLink(saved.getId()));
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
