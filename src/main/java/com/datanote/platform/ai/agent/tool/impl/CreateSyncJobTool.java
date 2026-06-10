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
    private final com.datanote.domain.governance.AssetDetailService assetDetailService; // 自动补全 sourceDsId(由 sourceDb 查所属数据源)
    private final com.datanote.domain.datasource.mapper.DnDatasourceMapper datasourceMapper; // 自动补全 targetDsId(数仓 Doris 数据源)

    /** 找数仓(Doris)数据源 ID, 供"抽到ODS/数仓"时自动补全 targetDsId, 免反问用户。 */
    private Long resolveWarehouseDsId() {
        try {
            com.datanote.domain.datasource.model.DnDatasource d = datasourceMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.datanote.domain.datasource.model.DnDatasource>()
                            .eq("type", "Doris").last("LIMIT 1"));
            return d == null ? null : d.getId();
        } catch (Exception e) { return null; }
    }

    @Override public String name() { return "create_sync_job"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "新建『数据同步』模块的同步任务(库到库/异构源通用同步, 写操作, 需人工审批)。"
                + "【注意: 若是『把表抽到Doris数仓ODS层/新建ODS任务/拉数到数仓』, 请改用 create_ods_table(数据开发ODS层), 不要用本工具】。"
                + "建成后在数据同步模块列表可见, 可手动运行/定时调度。"
                + "参数 jobName、syncMode(FULL/INCREMENTAL/CDC) 必填; sourceDsId 可不填(给 sourceDb 即按库自动识别); "
                + "targetDsId 可不填(默认数仓 Doris 数据源); targetDb/writeMode(UPSERT/INSERT/INSERT_IGNORE)/scheduleCron/tableConfig 可选。"
                + "【这些可推导/可默认的参数不必反问用户, 直接调用即可】。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"jobName\":{\"type\":\"string\",\"required\":true,\"desc\":\"任务名称\"},"
                + "\"targetDsId\":{\"type\":\"number\",\"required\":false,\"desc\":\"目标数据源ID, 不填默认数仓Doris\"},"
                + "\"syncMode\":{\"type\":\"string\",\"required\":true,\"desc\":\"FULL/INCREMENTAL/CDC\"},"
                + "\"sourceDb\":{\"type\":\"string\",\"required\":false,\"desc\":\"源库名(给此即自动识别 sourceDsId)\"},"
                + "\"sourceDsId\":{\"type\":\"number\",\"required\":false,\"desc\":\"源数据源ID, 不填则按 sourceDb 自动识别\"},"
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
            String sourceDb = AgentArgs.str(args, "sourceDb");
            if (jobName == null) return AiToolResult.fail("bad_arguments", "jobName 不能为空");
            // 自动补全: sourceDsId 未给则按 sourceDb 从元数据识别(免反问用户)
            if (sourceDsId == null && sourceDb != null) sourceDsId = assetDetailService.resolveDatasourceId(sourceDb, null);
            if (sourceDsId == null) return AiToolResult.fail("bad_arguments", "无法确定源数据源: 请给 sourceDb(将按库自动识别源数据源)或直接给 sourceDsId");
            // 自动补全: targetDsId 未给则默认数仓(Doris)数据源(抽到ODS/数仓的常见目标), 免反问用户
            if (targetDsId == null) targetDsId = resolveWarehouseDsId();
            if (targetDsId == null) return AiToolResult.fail("bad_arguments", "无法确定目标数据源: 未找到数仓(Doris)数据源, 请直接给 targetDsId");
            if (syncMode == null) return AiToolResult.fail("bad_arguments", "syncMode 不能为空(FULL/INCREMENTAL/CDC)");
            DnSyncJob job = new DnSyncJob();
            job.setJobName(jobName);
            job.setSourceDsId(sourceDsId);
            job.setTargetDsId(targetDsId);
            job.setSyncMode(syncMode.toUpperCase());
            job.setSourceDb(sourceDb);
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
