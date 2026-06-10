package com.datanote.platform.ai.agent.tool.impl;

import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * ask_user：向用户提问以征求决策/协助(人在环交互, 自由意志·人机协同)。
 * 当 agent 面临【需用户拍板的选择】或【信息不足需澄清】时调用, 主循环会拦截并暂停会话(wait_input),
 * 把结构化问题回传前端渲染【卡片提示框】, 用户选择后经 /answer 注入答案续跑。
 * 本工具不真正"执行"(由 AiAgentService 拦截暂停), invoke 仅作 schema 校验占位。
 */
@Component
public class AskUserTool implements AiTool {

    @Override public String name() { return "ask_user"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "向用户提问征求决策或澄清(会弹出卡片让用户选择, 暂停等待其回答后再继续)。"
                + "当面临需用户拍板的关键选择(如选哪个数据源/哪种建表策略)、或信息不足需澄清时调用。"
                + "参数 questions 为数组, 每项: {header:简短标签, question:问题原文, multiSelect:是否多选(默认false), "
                + "options:[{label:选项名, desc:选项说明(可空)}]}。用户可选预置项或自填(Other)。一次最多问 1~4 个问题。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"questions\":{\"type\":\"array\",\"required\":true,\"desc\":\"[{header,question,multiSelect,options:[{label,desc}]}]\"}}";
    }
    @Override public boolean readOnly() { return true; } // 交互元工具: 不写业务数据, 不触审批/护栏
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        // 实际暂停由 AiAgentService 主循环按工具名拦截处理; 此处仅基本校验占位。
        JsonNode qs = args == null ? null : args.get("questions");
        if (qs == null || !qs.isArray() || qs.size() == 0) {
            return AiToolResult.fail("bad_arguments", "questions 需为非空数组");
        }
        return AiToolResult.ok("ask_user 已受理");
    }
}
