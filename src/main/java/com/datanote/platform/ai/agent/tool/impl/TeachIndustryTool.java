package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.engine.IndustryKnowledgeService;
import com.datanote.platform.ai.agent.model.DnAiIndustrySop;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具: 沉淀行业经验/业务流程SOP。把一套标准业务流程/报表口径/坑记成可复用知识, 供后续小白照做。需审批。 */
@Component
@RequiredArgsConstructor
public class TeachIndustryTool implements AiTool {

    private final IndustryKnowledgeService industryKnowledgeService;

    @Override public String name() { return "teach_industry"; }
    @Override public String group() { return "industry"; }
    @Override public String description() {
        return "沉淀/教学行业经验: 把一套【业务流程/报表开发口径/指标口径/坑】记成可复用SOP(按业务域), 供后续小白照做。完成一类业务流后可调用沉淀。参数 title、content 必填; domain 业务域、type(flow业务流程/report报表/caliber口径/pitfall坑/glossary术语) 建议填。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"title\":{\"type\":\"string\",\"required\":true,\"desc\":\"SOP标题(简明)\"},"
             + "\"content\":{\"type\":\"string\",\"required\":true,\"desc\":\"标准步骤/口径/SQL模板/注意事项(Markdown)\"},"
             + "\"domain\":{\"type\":\"string\",\"required\":false,\"desc\":\"业务域(销售/库存/财务/会员; 缺省 global)\"},"
             + "\"type\":{\"type\":\"string\",\"required\":false,\"desc\":\"flow/report/caliber/pitfall/glossary\"},"
             + "\"trigger\":{\"type\":\"string\",\"required\":false,\"desc\":\"触发词/适用场景(便于后续召回)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "assistant:use"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String title = AgentArgs.str(args, "title");
            String content = AgentArgs.str(args, "content");
            if (title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty())
                return AiToolResult.fail("bad_arguments", "title 与 content 不能为空");
            String domain = AgentArgs.str(args, "domain");
            String type = AgentArgs.str(args, "type");
            String trigger = AgentArgs.str(args, "trigger");
            String editor = ctx == null ? null : ctx.getUserName();
            DnAiIndustrySop s = industryKnowledgeService.saveSop(domain, type, title, content, trigger, "taught", "active", editor);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", s.getId());
            out.put("domain", s.getDomain());
            out.put("title", s.getTitle());
            out.put("message", "已沉淀业务流程SOP「" + s.getTitle() + "」(域:" + s.getDomain() + "), 后续同类任务可被引导复用。");
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
