package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.StandardService;
import com.datanote.domain.governance.model.DnStandardCheckRun;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 运行数据标准符合性检查。 */
@Component
@RequiredArgsConstructor
public class RunStandardCheckTool implements AiTool {

    private final StandardService standardService;

    @Override public String name() { return "run_standard_check"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "运行数据标准符合性检查(写一行检查快照)。scope 可选: 空=全量, 'db'=库级, 'db.table'=表级。全量扫描可能耗时数秒。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"scope\":{\"type\":\"string\",\"required\":false,\"desc\":\"检查范围: 空=全量, 'db'=库级, 'db.table'=表级\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "governance:standard"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String scope = AgentArgs.str(args, "scope"); // null 表示全量
            DnStandardCheckRun run = standardService.runCheck(scope);
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", run.getId());
            summary.put("scope", run.getScope());
            summary.put("totalCount", run.getTotalCount());
            summary.put("violationCount", run.getViolationCount());
            summary.put("passRate", run.getPassRate());
            summary.put("createdAt", run.getCreatedAt());
            out.put("check", summary);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
