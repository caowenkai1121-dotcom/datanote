package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.datasource.MetadataCrawlerService;
import com.datanote.domain.metadata.model.DnMetaCollectLog;
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
public class TriggerMetadataCrawlTool implements AiTool {

    private final MetadataCrawlerService metadataCrawlerService;

    @Override public String name() { return "trigger_metadata_crawl"; }
    @Override public String group() { return "catalog"; }
    @Override public String description() {
        return "触发对指定数据源的元数据采集(刷新数据地图), 完成后自动同步向量库/图库。可能耗时数十秒(大库)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"datasourceId\":{\"type\":\"number\",\"required\":true,\"desc\":\"数据源 ID\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "catalog:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long datasourceId = AgentArgs.longVal(args, "datasourceId");
            if (datasourceId == null) return AiToolResult.fail("bad_arguments", "datasourceId 不能为空");
            DnMetaCollectLog log = metadataCrawlerService.crawlDatasource(datasourceId);
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("datasourceId", log.getDatasourceId());
            summary.put("status", log.getStatus());
            summary.put("tableCount", log.getTableCount());
            summary.put("columnCount", log.getColumnCount());
            summary.put("durationMs", log.getDurationMs());
            summary.put("startedAt", log.getStartedAt());
            summary.put("finishedAt", log.getFinishedAt());
            out.put("result", summary);
            out.put("note", "采集完成, 数据地图已更新");
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
