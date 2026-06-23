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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读工具: 召回行业经验(业务流程SOP + 业务域画像)。做业务流/报表/问口径前先查, 拿到标准步骤再执行。 */
@Component
@RequiredArgsConstructor
public class IndustryRecallTool implements AiTool {

    private final IndustryKnowledgeService industryKnowledgeService;

    @Override public String name() { return "industry_recall"; }
    @Override public String group() { return "industry"; }
    @Override public String description() {
        return "召回行业经验: 按业务域/关键词查已沉淀的【业务流程SOP/报表口径/坑】与业务域画像。做业务流程、报表开发或回答业务口径前先调用, 拿到标准步骤与口径再执行, 避免臆测。参数 query 建议填(业务诉求关键词), domain 可选(业务域名)。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"query\":{\"type\":\"string\",\"required\":false,\"desc\":\"业务诉求/关键词\"},\"domain\":{\"type\":\"string\",\"required\":false,\"desc\":\"业务域名(如 销售/库存/财务/会员)\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String query = AgentArgs.str(args, "query");
            String domain = AgentArgs.str(args, "domain");
            List<DnAiIndustrySop> sops = industryKnowledgeService.recallSop(domain, query, 6);
            String profile = industryKnowledgeService.industryProfileText(query == null ? (domain == null ? "" : domain) : query);
            if (profile == null && sops.isEmpty()) industryKnowledgeService.bootstrapIfEmpty(); // 首次为空: 自动触发归纳, 下次即有料
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profile", profile == null ? "(暂无行业画像, 已自动触发后台归纳; 本次先据元数据/用户澄清推进, 稍后会有积累)" : profile);
            List<Map<String, Object>> list = new ArrayList<>();
            for (DnAiIndustrySop s : sops) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("domain", s.getDomain());
                m.put("type", s.getSopType());
                m.put("title", s.getTitle());
                m.put("content", s.getContent());
                list.add(m);
            }
            out.put("sops", list);
            out.put("hint", list.isEmpty() ? "暂无匹配SOP; 据画像/指标口径规划标准步骤, 完成后可用 teach_industry 沉淀。" : "据以上SOP给用户列标准步骤并逐步核实执行。");
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
