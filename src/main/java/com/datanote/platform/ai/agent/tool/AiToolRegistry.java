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
    /** 全量清单缓存: byName 构造后不可变, 全量序列化结果恒定, 懒加载一次复用(每轮 run/resume/tools 端点都调) */
    private volatile String fullManifestCache;

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

    /** 生成机读工具清单（注入 prompt 供 LLM 自发现）。全量清单恒定, 懒加载缓存避免每次重复序列化。 */
    public String toToolsManifestJson() {
        String c = fullManifestCache;
        if (c == null) {
            c = toToolsManifestJson(null);
            fullManifestCache = c;
        }
        return c;
    }

    /** 按谓词过滤的机读工具清单(子代理只读受限清单复用)。filter=null 则全量。 */
    public String toToolsManifestJson(java.util.function.Predicate<AiTool> filter) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiTool t : byName.values()) {
            if (filter != null && !filter.test(t)) continue;
            // caveman 精简: 只留 LLM 选工具真正需要的字段(name/description/readOnly/params);
            // group/risk LLM 不参考(审批由后端护栏强制), 去掉省每轮 prompt token
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("readOnly", t.readOnly());
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
