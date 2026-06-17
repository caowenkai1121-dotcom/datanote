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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 只读: 数据同步全局大盘(任务总数/按状态/按模式/已上线调度数)。聚合 SyncJobService.list, 供首页简报与同步全局问答。 */
@Component
@RequiredArgsConstructor
public class SyncDashboardTool implements AiTool {

    private final SyncJobService syncJobService;

    @Override public String name() { return "sync_dashboard"; }
    @Override public String group() { return "sync"; }
    @Override public String description() {
        return "查数据同步全局大盘: 同步任务总数、按状态(CREATED/RUNNING/STOPPED/PAUSED/FAILED)分布、按同步模式(FULL/INCREMENTAL/CDC)分布、已上线定时调度数。回答同步整体态势或生成简报时用。无参数。";
    }
    @Override public String paramsSchemaJson() { return "{}"; }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "dbsync:view"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            List<DnSyncJob> jobs = syncJobService.list();
            Map<String, Integer> byStatus = new TreeMap<>();
            Map<String, Integer> byMode = new TreeMap<>();
            int online = 0;
            if (jobs != null) {
                for (DnSyncJob j : jobs) {
                    if (j == null) continue;
                    String st = j.getStatus() == null ? "UNKNOWN" : j.getStatus().toUpperCase();
                    byStatus.merge(st, 1, Integer::sum);
                    String md = j.getSyncMode() == null ? "UNKNOWN" : j.getSyncMode().toUpperCase();
                    byMode.merge(md, 1, Integer::sum);
                    if ("online".equalsIgnoreCase(j.getScheduleStatus())) online++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobsTotal", jobs == null ? 0 : jobs.size());
            out.put("byStatus", byStatus);
            out.put("bySyncMode", byMode);
            out.put("scheduleOnline", online);
            out.put("_deeplink", AgentArgs.link("dbsync", null));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
