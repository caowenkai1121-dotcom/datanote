package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.governance.QualityService;
import com.datanote.domain.governance.mapper.DnQualityRuleMapper;
import com.datanote.domain.governance.model.DnQualityRule;
import com.datanote.domain.governance.model.DnQualityRun;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 工具: 运行一条数据质量规则(薄适配 QualityService.executeRule)。
 * 补 agent "能 create_quality_rule 建规则却不能跑"的缺口; 跑完返回通过率/失败数/错误样例, 便于据真值判断质量。
 */
@Component
@RequiredArgsConstructor
public class RunQualityRuleTool implements AiTool {

    private final QualityService qualityService;
    private final DnQualityRuleMapper ruleMapper;

    @Override public String name() { return "run_quality_rule"; }
    @Override public String group() { return "quality"; }
    @Override public String description() {
        return "运行一条数据质量规则(写操作: 落执行记录, 需审批): 按 ruleId 执行校验, 返回 总数/通过数/失败数/通过率/错误样例。"
                + "用户说『跑一下这条质量规则/检查这个表的质量/校验数据质量』且已有规则时用本工具(传 ruleId); 没规则先 create_quality_rule。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"ruleId\":{\"type\":\"number\",\"required\":true,\"desc\":\"质量规则ID(create_quality_rule 返回)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "governance:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long ruleId = AgentArgs.longVal(args, "ruleId");
            if (ruleId == null) return AiToolResult.fail("bad_arguments", "ruleId 不能为空");
            DnQualityRule rule = ruleMapper.selectById(ruleId);
            if (rule == null) return AiToolResult.fail("not_found", "质量规则不存在: " + ruleId + "(可先 create_quality_rule 建规则)");
            DnQualityRun run = qualityService.executeRule(rule);
            if (run == null) return AiToolResult.fail("exec_failed", "执行返回空结果");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ruleId", ruleId);
            out.put("ruleName", rule.getRuleName());
            out.put("runStatus", run.getRunStatus());
            out.put("totalCount", run.getTotalCount());
            out.put("passCount", run.getPassCount());
            out.put("failCount", run.getFailCount());
            out.put("passRate", run.getPassRate());
            out.put("durationMs", run.getDurationMs());
            if (run.getErrorMsg() != null) out.put("errorMsg", run.getErrorMsg());
            String es = run.getErrorSample();
            if (es != null && !es.isEmpty()) out.put("errorSample", es.length() > 600 ? es.substring(0, 600) : es);
            out.put("note", "质量检查已执行; 失败数/通过率为真值, 据此判断数据质量, 勿臆造");
            return AiToolResult.ok(out);
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
