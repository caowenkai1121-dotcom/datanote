package com.datanote.platform.ai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：启动期 @Autowired List&lt;AiTool&gt; 自动收集所有工具 bean。
 * name 全局唯一从架构上消除重复模块；toToolsManifestJson 生成机读清单注入 prompt。
 */
@Component
public class AiToolRegistry {

    private final Map<String, AiTool> byName = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public AiToolRegistry(List<AiTool> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        if (tools != null) {
            for (AiTool t : tools) {
                if (t != null && t.name() != null && !t.name().trim().isEmpty()) {
                    byName.put(t.name(), t);
                }
            }
        }
    }

    public AiTool find(String name) {
        return name == null ? null : byName.get(name);
    }

    public Collection<AiTool> all() {
        return byName.values();
    }

    public int size() {
        return byName.size();
    }

    /** 生成机读工具清单（注入 prompt 供 LLM 自发现）。 */
    public String toToolsManifestJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiTool t : byName.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("group", t.group());
            m.put("description", t.description());
            m.put("readOnly", t.readOnly());
            m.put("risk", t.risk() == null ? "LOW" : t.risk().name());
            Object params;
            try {
                params = objectMapper.readTree(t.paramsSchemaJson() == null ? "{}" : t.paramsSchemaJson());
            } catch (Exception e) {
                params = t.paramsSchemaJson();
            }
            m.put("params", params);
            list.add(m);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
