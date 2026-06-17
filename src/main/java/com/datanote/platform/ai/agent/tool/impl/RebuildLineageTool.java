package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.orchestration.LineageEdgeService;
import com.datanote.domain.orchestration.SqlLineageService;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 高风险写工具(HIGH): 重建血缘边。每次需审批。 */
@Component
@RequiredArgsConstructor
public class RebuildLineageTool implements AiTool {

    private final LineageEdgeService lineageEdgeService;
    private final SqlLineageService sqlLineageService;

    @Override public String name() { return "rebuild_lineage"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "【破坏性重建】重建血缘边。mode=sync_mapping 基于同步任务字段映射重建(删除所有 source=MAPPING 边后重建); " +
               "mode=sql_parse 解析脚本SQL重建(删除所有 source=SQL 边后重建)。" +
               "手工录入的 MANUAL 边不受影响。高风险写操作, 每次需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"mode\":{\"type\":\"string\",\"required\":true,\"desc\":\"重建模式: sync_mapping | sql_parse\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }
    @Override public String requiredPerm() { return "governance:manage"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String mode = AgentArgs.str(args, "mode");
            if (mode == null) return AiToolResult.fail("bad_arguments", "mode 不能为空(sync_mapping | sql_parse)");
            int rebuilt;
            if ("sync_mapping".equals(mode)) {
                rebuilt = lineageEdgeService.rebuildFromSyncJobs();
            } else if ("sql_parse".equals(mode)) {
                rebuilt = sqlLineageService.rebuildFromScripts();
            } else {
                return AiToolResult.fail("bad_arguments", "mode 必须为 sync_mapping 或 sql_parse, 实际: " + mode);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rebuilt", rebuilt);
            out.put("mode", mode);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
