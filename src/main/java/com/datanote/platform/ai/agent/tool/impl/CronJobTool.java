package com.datanote.platform.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.agent.engine.CronScheduler;
import com.datanote.platform.ai.agent.mapper.DnAiCronJobMapper;
import com.datanote.platform.ai.agent.model.DnAiCronJob;
import com.datanote.platform.ai.agent.tool.AgentContext;
import com.datanote.platform.ai.agent.tool.AiTool;
import com.datanote.platform.ai.agent.tool.AiToolResult;
import com.datanote.platform.ai.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * cron_job：让用户/agent 用人话给 agent 排定时自治任务(Wave5)。到点在无人值守 cron 会话跑该 prompt。
 * action=create(name/schedule/prompt)/list/pause(id)/resume(id)/remove(id)。
 * 护栏: 总启用任务 ≤ MAX_JOBS; everyNm 最小间隔 ≥ MIN_INTERVAL_MIN; schedule 必须可解析; cron 会话内本工具被禁(防递归)。
 */
@Component
@RequiredArgsConstructor
public class CronJobTool implements AiTool {

    private final DnAiCronJobMapper cronMapper;

    private static final int MAX_JOBS = 10;
    private static final int MIN_INTERVAL_MIN = 5;

    @Override public String name() { return "cron_job"; }
    @Override public String group() { return "agent"; }
    @Override public String description() {
        return "为 agent 排定时自治任务(到点在无人值守会话自动跑指定提示, 如每天巡检/周期报告)。"
                + "action=create: name(任务名)+schedule(everyNm/everyNh/everyNd 或 Spring 6段cron, 如 '0 0 9 * * ?' 每天9点)+prompt(到点要做什么); "
                + "action=list: 列出任务; pause/resume/remove: 传 id。"
                + "定时任务里不能写业务数据(会挂起审批), 不能再排程。最小间隔" + MIN_INTERVAL_MIN + "分钟, 最多" + MAX_JOBS + "个。";
    }
    @Override public String paramsSchemaJson() {
        return "{\"action\":{\"type\":\"string\",\"required\":true,\"desc\":\"create/list/pause/resume/remove\"},"
                + "\"name\":{\"type\":\"string\",\"required\":false},"
                + "\"schedule\":{\"type\":\"string\",\"required\":false,\"desc\":\"everyNm/everyNh/everyNd 或 6段cron\"},"
                + "\"prompt\":{\"type\":\"string\",\"required\":false,\"desc\":\"到点要 agent 做什么\"},"
                + "\"id\":{\"type\":\"number\",\"required\":false}}";
    }
    @Override public boolean readOnly() { return true; } // 元工具: 管 agent 自身排程, 不碰业务数据; cron 运行本身受护栏约束
    @Override public RiskLevel risk() { return RiskLevel.LOW; }

    @Override
    public AiToolResult invoke(JsonNode args, AgentContext ctx) {
        String action = AgentArgs.str(args, "action");
        if (action == null) return AiToolResult.fail("bad_arguments", "action 不能为空(create/list/pause/resume/remove)");
        String owner = ctx == null ? null : ctx.getUserName();
        LocalDateTime now = LocalDateTime.now();

        switch (action) {
            case "create": {
                String name = AgentArgs.str(args, "name");
                String schedule = AgentArgs.str(args, "schedule");
                String prompt = AgentArgs.str(args, "prompt");
                if (name == null || schedule == null || prompt == null) {
                    return AiToolResult.fail("bad_arguments", "create 需 name + schedule + prompt");
                }
                LocalDateTime next = CronScheduler.computeNext(schedule, now);
                if (next == null) {
                    return AiToolResult.fail("bad_arguments", "schedule 无法解析(用 everyNm/everyNh/everyNd 或 6段cron, 如 '0 0 9 * * ?')");
                }
                // 最小间隔护栏(everyNm 与 6 段 cron 统一下限, 防高频自治刷爆)
                String s = schedule.trim().toLowerCase();
                if (s.matches("every\\d+m")) {
                    int n = Integer.parseInt(s.replaceAll("\\D", ""));
                    if (n < MIN_INTERVAL_MIN) return AiToolResult.fail("forbidden", "最小间隔 " + MIN_INTERVAL_MIN + " 分钟");
                } else if (!s.startsWith("every")) {
                    // 6 段 cron: 连算相邻两次执行, 间隔 < 下限则拒
                    LocalDateTime second = CronScheduler.computeNext(schedule, next.plusSeconds(1));
                    if (second != null && java.time.Duration.between(next, second).toMinutes() < MIN_INTERVAL_MIN) {
                        return AiToolResult.fail("forbidden", "cron 执行间隔过密, 最小间隔 " + MIN_INTERVAL_MIN + " 分钟");
                    }
                }
                Long cnt = cronMapper.selectCount(new QueryWrapper<DnAiCronJob>().eq("enabled", 1));
                if (cnt != null && cnt >= MAX_JOBS) return AiToolResult.fail("forbidden", "启用任务已达上限 " + MAX_JOBS + ", 请先停用/删除");

                DnAiCronJob job = new DnAiCronJob();
                job.setName(cap(name, 200));
                job.setScheduleCron(cap(schedule.trim(), 120));
                job.setPrompt(prompt);
                job.setEnabled(1);
                job.setSilent(0);
                job.setOwner(owner);
                job.setNextRun(next);
                job.setRunCount(0);
                job.setCreatedAt(now);
                job.setUpdatedAt(now);
                cronMapper.insert(job);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", job.getId());
                d.put("name", job.getName());
                d.put("schedule", job.getScheduleCron());
                d.put("nextRun", String.valueOf(next));
                d.put("note", "已排程, 到点自动执行(无人值守, 写动作会挂起审批)");
                return AiToolResult.ok(d);
            }
            case "list": {
                List<DnAiCronJob> jobs = cronMapper.selectList(new QueryWrapper<DnAiCronJob>()
                        .eq(owner != null, "owner", owner) // owner 作用域(匿名看全部)
                        .orderByDesc("enabled").orderByAsc("next_run").last("LIMIT 50"));
                List<Map<String, Object>> out = new ArrayList<>();
                if (jobs != null) for (DnAiCronJob j : jobs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", j.getId()); m.put("name", j.getName()); m.put("schedule", j.getScheduleCron());
                    m.put("enabled", j.getEnabled()); m.put("nextRun", String.valueOf(j.getNextRun()));
                    m.put("lastStatus", j.getLastStatus()); m.put("runCount", j.getRunCount());
                    out.add(m);
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("count", out.size()); d.put("jobs", out);
                return AiToolResult.ok(d);
            }
            case "pause":
            case "resume": {
                Long id = idOf(args);
                if (id == null) return AiToolResult.fail("bad_arguments", action + " 需 id");
                int en = "resume".equals(action) ? 1 : 0;
                int n = cronMapper.update(null, new UpdateWrapper<DnAiCronJob>().eq("id", id)
                        .eq(owner != null, "owner", owner) // owner 作用域防越权
                        .set("enabled", en).set("updated_at", now));
                return n > 0 ? AiToolResult.ok("任务 " + id + (en == 1 ? " 已启用" : " 已停用")) : AiToolResult.fail("not_found", "任务不存在或无权");
            }
            case "remove": {
                Long id = idOf(args);
                if (id == null) return AiToolResult.fail("bad_arguments", "remove 需 id");
                int n = cronMapper.delete(new QueryWrapper<DnAiCronJob>()
                        .eq("id", id).eq(owner != null, "owner", owner)); // owner 作用域防越权删
                return n > 0 ? AiToolResult.ok("任务 " + id + " 已删除") : AiToolResult.fail("not_found", "任务不存在或无权");
            }
            default:
                return AiToolResult.fail("bad_arguments", "未知 action: " + action);
        }
    }

    private Long idOf(JsonNode args) {
        Long v = AgentArgs.longVal(args, "id"); // 用 longVal 避免大 id 被截断为 int
        return (v == null || v < 0) ? null : v;
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
