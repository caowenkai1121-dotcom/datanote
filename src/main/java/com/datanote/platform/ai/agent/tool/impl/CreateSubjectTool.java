package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.common.SubjectService;
import com.datanote.domain.metadata.model.DnSubject;
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
public class CreateSubjectTool implements AiTool {

    private final SubjectService subjectService;

    @Override public String name() { return "create_subject"; }
    @Override public String group() { return "catalog"; }
    @Override public String description() {
        return "新建数据主题域(可挂在 parentId 下成子域, 不填则建顶级域); 用于组织数据资产分层。写操作需审批。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"name\":{\"type\":\"string\",\"required\":true,\"desc\":\"主题域名称\"},"
                + "\"parentId\":{\"type\":\"number\",\"required\":false,\"desc\":\"父主题域 ID, 不填建顶级域\"},"
                + "\"layer\":{\"type\":\"string\",\"required\":false,\"desc\":\"业务分层标识\"},"
                + "\"layerType\":{\"type\":\"string\",\"required\":false,\"desc\":\"数仓分层 ODS/DWD/DIM/DWS/ADS\"},"
                + "\"sortOrder\":{\"type\":\"number\",\"required\":false,\"desc\":\"排序序号\"}}";
    }
    @Override public boolean readOnly() { return false; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return "catalog:edit"; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        try {
            String name = AgentArgs.str(args, "name");
            if (name == null) return AiToolResult.fail("bad_arguments", "name 不能为空");
            DnSubject subject = new DnSubject();
            subject.setName(name);
            subject.setParentId(AgentArgs.longVal(args, "parentId"));
            subject.setLayer(AgentArgs.str(args, "layer"));
            subject.setLayerType(AgentArgs.str(args, "layerType"));
            Long sortOrderLong = AgentArgs.longVal(args, "sortOrder");
            if (sortOrderLong != null) subject.setSortOrder(sortOrderLong.intValue());
            DnSubject saved = subjectService.create(subject);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", saved);
            return AiToolResult.ok(out);
        } catch (IllegalArgumentException e) {
            return AiToolResult.fail("bad_arguments", e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("exec_failed", e.getMessage());
        }
    }
}
