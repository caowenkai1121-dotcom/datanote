package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.datamodel.DataModelService;
import com.datanote.domain.datamodel.model.DnModel;
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
 * AI 工具: 数据模型层间生成/逆向(薄适配 DataModelService)。补 agent 此前只能存/送审/审批模型、
 * 无法自动【逻辑→物理生成 / 业务→逻辑生成 / 从物理表逆向建模】的缺口。
 *  - physical: 由逻辑模型生成物理模型(类型映射/命名规范)
 *  - logical : 由业务模型生成逻辑模型
 *  - reverse : 从已采集的物理表(dn_table_meta)逆向生成模型
 */
@Component
@RequiredArgsConstructor
public class DatamodelGenerateTool implements AiTool {

    private final DataModelService dataModelService;

    @Override public String name() { return "datamodel_generate"; }
    @Override public String group() { return "datamodel"; }
    @Override public String description() {
        return "数据模型层间生成/逆向(写操作, 需审批)。action: "
                + "physical(由逻辑模型生成物理模型, 传 modelId=逻辑模型id) / "
                + "logical(由业务模型生成逻辑模型, 传 modelId=业务模型id) / "
                + "reverse(从已采集物理表逆向生成模型, 传 tableMetaId[+subjectId])。"
                + "用户说『把逻辑模型生成物理/由业务模型生成逻辑/把这张表逆向成数据模型』时用本工具。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"action\":{\"type\":\"string\",\"required\":true,\"desc\":\"physical/logical/reverse\"},"
                + "\"modelId\":{\"type\":\"number\",\"required\":false,\"desc\":\"physical/logical 时的源模型id(逻辑/业务)\"},"
                + "\"tableMetaId\":{\"type\":\"number\",\"required\":false,\"desc\":\"reverse 时的物理表元数据id(dn_table_meta)\"},"
                + "\"subjectId\":{\"type\":\"number\",\"required\":false,\"desc\":\"reverse 时归属主题域id, 可选\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.MEDIUM; }
    @Override public String requiredPerm() { return "datamodel:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String action = AgentArgs.str(args, "action");
            if (action == null) return AiToolResult.fail("bad_arguments", "action 不能为空(physical/logical/reverse)");
            DnModel m;
            switch (action) {
                case "physical": {
                    Long id = AgentArgs.longVal(args, "modelId");
                    if (id == null) return AiToolResult.fail("bad_arguments", "physical 需传 modelId(逻辑模型id)");
                    m = dataModelService.generatePhysical(id);
                    break;
                }
                case "logical": {
                    Long id = AgentArgs.longVal(args, "modelId");
                    if (id == null) return AiToolResult.fail("bad_arguments", "logical 需传 modelId(业务模型id)");
                    m = dataModelService.generateLogical(id);
                    break;
                }
                case "reverse": {
                    Long tableMetaId = AgentArgs.longVal(args, "tableMetaId");
                    if (tableMetaId == null) return AiToolResult.fail("bad_arguments", "reverse 需传 tableMetaId(物理表元数据id)");
                    m = dataModelService.reverseFromTable(tableMetaId, AgentArgs.longVal(args, "subjectId"));
                    break;
                }
                default:
                    return AiToolResult.fail("bad_arguments", "action 仅支持 physical/logical/reverse");
            }
            if (m == null) return AiToolResult.fail("exec_failed", "生成失败: 返回空模型");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", action);
            out.put("modelId", m.getId());
            out.put("modelCode", m.getModelCode());
            out.put("modelName", m.getModelName());
            out.put("modelType", m.getModelType());
            out.put("note", "已生成模型 " + m.getModelName() + "(" + m.getModelType() + "); 可在数据模型模块查看/编辑, 送审后发布");
            return AiToolResult.ok(out);
        } catch (com.datanote.common.exception.BusinessException be) {
            return AiToolResult.fail("bad_arguments", be.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
