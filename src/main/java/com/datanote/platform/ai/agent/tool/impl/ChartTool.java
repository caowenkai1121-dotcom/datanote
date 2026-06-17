package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * chart：把已获取的数据渲染为图表卡(bar/line/pie/radar)。
 * 纯渲染指令，不访问任何数据源，无权限约束。
 * 前端通过 _chart 键识别并用 DN.* 图表组件渲染。
 */
@Component
@RequiredArgsConstructor
public class ChartTool implements AiTool {

    private static final Set<String> VALID_TYPES =
            new HashSet<>(Arrays.asList("bar", "line", "pie", "radar"));

    private final ObjectMapper objectMapper;

    @Override public String name() { return "chart"; }
    @Override public String group() { return "analysis"; }
    @Override public String description() {
        return "把数据渲染成图表卡(type: bar柱/line线/pie饼/radar雷达)。"
                + "data: bar/pie/radar 传 [{label,value},...]; line 传数值数组 [n1,n2,...]。"
                + "数值须来自 run_analysis 等工具的真实结果, 不可编造。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"type\":{\"type\":\"string\",\"required\":true,\"desc\":\"图表类型: bar|line|pie|radar\"},"
                + "\"title\":{\"type\":\"string\",\"required\":false,\"desc\":\"图表标题\"},"
                + "\"data\":{\"type\":\"any\",\"required\":true,\"desc\":\"图表数据: bar/pie/radar 传 [{label,value},...]; line 传 [n1,n2,...]\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }
    @Override public String requiredPerm() { return null; } // 纯渲染, 无数据访问

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String type = AgentArgs.str(args, "type");
        if (type == null) {
            return AiToolResult.fail("bad_arguments", "type 不能为空，可选值: bar|line|pie|radar");
        }
        type = type.toLowerCase().trim();
        if (!VALID_TYPES.contains(type)) {
            return AiToolResult.fail("bad_arguments", "不支持的图表类型: " + type + "，可选值: bar|line|pie|radar");
        }

        JsonNode dataNode = args == null ? null : args.get("data");
        if (dataNode == null || dataNode.isNull()) {
            return AiToolResult.fail("bad_arguments", "data 不能为空");
        }

        String title = AgentArgs.str(args, "title");

        // 将 data JsonNode 转为普通 Java 对象（List/Map），前端 JSON 序列化透传
        Object dataObj;
        try {
            dataObj = objectMapper.convertValue(dataNode, Object.class);
        } catch (Exception e) {
            return AiToolResult.fail("bad_arguments", "data 格式无法解析: " + e.getMessage());
        }

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", type);
        if (title != null) chart.put("title", title);
        chart.put("data", dataObj);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_chart", chart);
        return AiToolResult.ok(out);
    }
}
