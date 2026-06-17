package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.IssueService;
import com.datanote.domain.governance.model.DnGovernanceIssue;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具(MEDIUM): 新建治理工单。映射 UI 同款 IssueService.create, 建成在治理健康-工单中心可见。 */
@Component
@RequiredArgsConstructor
public class CreateGovernanceIssueTool implements AiTool {

    private final IssueService issueService;

    @Override public String name() { return "create_governance_issue"; }
    @Override public String group() { return "gov"; }
    @Override public String description() {
        return "新建治理工单(写操作, 需人工审批)。把发现的质量/规范/安全/血缘问题落成可追踪工单。建成在治理健康-工单中心可见。参数 title 必填; issueType(STANDARD/QUALITY/SECURITY/LINEAGE/LIFECYCLE/OTHER)、severity(HIGH/MEDIUM/LOW)、dimension、objectRef(库.表)、description 可选。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"工单标题\"},\"issueType\":{\"type\":\"string\",\"required\":false,\"desc\":\"类型,默认OTHER\"},\"severity\":{\"type\":\"string\",\"required\":false,\"desc\":\"HIGH/MEDIUM/LOW\"},\"dimension\":{\"type\":\"string\",\"required\":false},\"objectRef\":{\"type\":\"string\",\"required\":false,\"desc\":\"关联对象 库.表\"},\"description\":{\"type\":\"string\",\"required\":false}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "governance:issue"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String title = AgentArgs.str(args, "title");
            if (title == null) return AiToolResult.fail("bad_arguments", "title 不能为空");
            DnGovernanceIssue issue = new DnGovernanceIssue();
            issue.setTitle(title);
            String type = AgentArgs.str(args, "issueType");
            issue.setIssueType(type == null ? "OTHER" : type);
            issue.setSeverity(AgentArgs.str(args, "severity"));
            issue.setDimension(AgentArgs.str(args, "dimension"));
            issue.setObjectRef(AgentArgs.str(args, "objectRef"));
            issue.setDescription(AgentArgs.str(args, "description"));
            if (ctx != null && ctx.getUserName() != null) issue.setOwner(ctx.getUserName());
            DnGovernanceIssue saved = issueService.create(issue);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            Map<String, Object> ctxMap = new LinkedHashMap<>();
            ctxMap.put("gov", "health");
            out.put("_deeplink", AgentArgs.link("governance", ctxMap));
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
