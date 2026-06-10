package com.datanote.platform.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiMemorySkillMapper;
import com.datanote.platform.ai.agent.model.DnAiMemorySkill;
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

/**
 * skill_library：按需加载已沉淀的【操作技能】(R89, type=skill 的可照做有序步骤手册)。
 * 自学习记忆已会语义自动召回; 本工具让 agent 主动按需 list/view 具体技能全文(技能 commands 的精髓)。
 * action=list: 列技能(标题+适用); action=view: 按 title 取技能全文步骤。只读元工具。
 */
@Component
@RequiredArgsConstructor
public class SkillLibraryTool implements AiTool {

    private final DnAiMemorySkillMapper memoryMapper;

    @Override public String name() { return "skill_library"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "查阅已沉淀的【操作技能】(可照做的有序步骤手册)。action=list 列出可用技能(标题+适用场景); "
                + "action=view + title 取某技能全文步骤。开始一类标准任务(如建表/同步排错)前可先 list 看有无现成套路, 再 view 照做。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"action\":{\"type\":\"string\",\"required\":true,\"desc\":\"list/view\"},"
                + "\"title\":{\"type\":\"string\",\"required\":false,\"desc\":\"view 时: 技能标题\"}}";
    }
    @Override public boolean readOnly() { return true; }
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String action = AgentArgs.str(args, "action");
        if (action == null) return AiToolResult.fail("bad_arguments", "action 不能为空(list/view)");
        String owner = ctx == null ? null : ctx.getUserName();
        boolean anon = owner == null || "anonymous".equals(owner);

        if ("list".equals(action)) {
            QueryWrapper<DnAiMemorySkill> qw = new QueryWrapper<DnAiMemorySkill>()
                    .eq("type", "skill").eq("status", "active");
            if (!anon) qw.and(w -> w.eq("owner", owner).or().isNull("owner"));
            qw.orderByDesc("hit_count").orderByDesc("updated_at").last("LIMIT 50");
            List<DnAiMemorySkill> skills = memoryMapper.selectList(qw);
            List<Map<String, Object>> out = new ArrayList<>();
            if (skills != null) for (DnAiMemorySkill s : skills) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", s.getTitle());
                m.put("trigger", s.getTriggerHint());
                m.put("hitCount", s.getHitCount());
                out.add(m);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("count", out.size());
            d.put("skills", out);
            return AiToolResult.ok(d);
        }
        if ("view".equals(action)) {
            String title = AgentArgs.str(args, "title");
            if (title == null) return AiToolResult.fail("bad_arguments", "view 需 title");
            QueryWrapper<DnAiMemorySkill> qw = new QueryWrapper<DnAiMemorySkill>()
                    .eq("type", "skill").eq("status", "active").eq("title", title);
            if (!anon) qw.and(w -> w.eq("owner", owner).or().isNull("owner"));
            DnAiMemorySkill s = memoryMapper.selectOne(qw.last("LIMIT 1"));
            if (s == null) return AiToolResult.fail("not_found", "未找到技能: " + title + "(可先 action=list 看可用技能)");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("title", s.getTitle());
            d.put("trigger", s.getTriggerHint());
            d.put("steps", s.getContent());
            return AiToolResult.ok(d);
        }
        return AiToolResult.fail("bad_arguments", "未知 action: " + action + "(应 list/view)");
    }
}
