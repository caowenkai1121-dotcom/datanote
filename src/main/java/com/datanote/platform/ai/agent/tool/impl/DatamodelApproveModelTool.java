package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.DataModelService;
import com.datanote.domain.datamodel.model.DnModelChange;
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
 * AI 工具: 审批数据模型发布。薄适配器 → DataModelService.review。
 * reviewer 身份由服务层从 CurrentUserUtil 取, 工具层不接受 reviewer 入参(身份完整性)。
 */
@Component
@RequiredArgsConstructor
public class DatamodelApproveModelTool implements AiTool {

    private final DataModelService dataModelService;

    @Override public String name() { return "datamodel_approve_model"; }
    @Override public String group() { return "datamodel"; }
    @Override public String description() {
        return "审批数据模型发布(approved→模型 PUBLISHED + version+1 + 版本快照; rejected 须填 comment)。"
             + "禁自批(发起人≠审批人, admin 例外, 由服务层守卫)。高风险写操作, 每次需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"changeId\":{\"type\":\"number\",\"required\":true,\"desc\":\"变更工单 id(由 datamodel_submit_for_approval 返回)\"},"
             + "\"decision\":{\"type\":\"string\",\"required\":true,\"desc\":\"审批决定: approved(通过) 或 rejected(驳回)\"},"
             + "\"comment\":{\"type\":\"string\",\"required\":false,\"desc\":\"审批意见(驳回时必填)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.HIGH; }
    @Override public String requiredPerm() { return "datamodel:approve"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long changeId = AgentArgs.longOrCtx(args, "changeId", ctx);
            if (changeId == null) return AiToolResult.fail("bad_arguments", "changeId 不能为空");
            String decision = AgentArgs.str(args, "decision");
            if (decision == null) return AiToolResult.fail("bad_arguments", "decision 不能为空(approved/rejected)");
            if (!"approved".equals(decision) && !"rejected".equals(decision)) {
                return AiToolResult.fail("bad_arguments", "decision 须为 approved 或 rejected");
            }
            String comment = AgentArgs.str(args, "comment");
            // 驳回时 comment 必填, 与服务层校验一致(此处提前给友好提示)
            if ("rejected".equals(decision) && (comment == null || comment.trim().isEmpty())) {
                return AiToolResult.fail("bad_arguments", "驳回必须填写 comment(审批意见)");
            }

            // reviewer 由服务层内部从 CurrentUserUtil.currentUser() 取, 此处不传
            DnModelChange change = dataModelService.review(changeId, decision, comment);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reviewed", true);
            out.put("changeId", changeId);
            out.put("decision", decision);
            out.put("change", change);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (IllegalStateException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (BusinessException e) {
            return AiToolResult.fail("conflict", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
