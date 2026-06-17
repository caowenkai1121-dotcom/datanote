package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
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
public class UpdateColumnBusinessMetaTool implements AiTool {

    private final DnColumnMetaMapper dnColumnMetaMapper;

    @Override public String name() { return "update_column_business_meta"; }
    @Override public String group() { return "catalog"; }
    @Override public String description() {
        return "更新某字段的业务属性(业务名/业务描述/标签/敏感级别/敏感类型), 用于数据治理人工注释。只改业务属性, 不动采集来的技术字段(类型/可空等)。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"id\":{\"type\":\"number\",\"required\":true,\"desc\":\"dn_column_meta 行 ID\"},"
                + "\"businessName\":{\"type\":\"string\",\"required\":false,\"desc\":\"业务名称\"},"
                + "\"businessDesc\":{\"type\":\"string\",\"required\":false,\"desc\":\"业务描述\"},"
                + "\"tags\":{\"type\":\"string\",\"required\":false,\"desc\":\"标签, 逗号分隔\"},"
                + "\"securityLevel\":{\"type\":\"string\",\"required\":false,\"desc\":\"敏感级别(如 L1/L2/L3)\"},"
                + "\"sensitiveType\":{\"type\":\"string\",\"required\":false,\"desc\":\"敏感类型(如 手机号/身份证)\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "catalog:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            Long id = AgentArgs.longVal(args, "id");
            if (id == null) return AiToolResult.fail("bad_arguments", "id 不能为空");
            DnColumnMeta meta = new DnColumnMeta();
            meta.setId(id);
            meta.setBusinessName(AgentArgs.str(args, "businessName"));
            meta.setBusinessDesc(AgentArgs.str(args, "businessDesc"));
            meta.setTags(AgentArgs.str(args, "tags"));
            meta.setSecurityLevel(AgentArgs.str(args, "securityLevel"));
            meta.setSensitiveType(AgentArgs.str(args, "sensitiveType"));
            dnColumnMetaMapper.updateById(meta);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updated", true);
            out.put("id", id);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
