package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiCronJobMapper;
import com.datanote.platform.ai.agent.model.DnAiCronJob;
import com.datanote.platform.ai.agent.tool.AgentContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI agent 定时自治调度(Wave5, 借鉴 hermes cron)。每分钟 tick 取到期任务, 在【无人值守 cron 会话】跑 agent。
 *
 * 健壮/红线:
 *  - 执行前【先推进 next_run】(原子占行) → 崩溃幂等至多一次, 防重入/多实例重复跑。
 *  - schedule 算不出下次时间 → 置 error 状态, 【不静默禁用】(blueprint 反模式防御)。
 *  - 任务在 cron 模式跑: 禁 cron_job(防递归排程)/ask_user(无人应答); 写动作仍走审批(挂起, 不自动执行)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CronScheduler {

    private final DnAiCronJobMapper cronMapper;
    private final AiAgentService aiAgentService;
    private final AgentPermResolver permResolver;

    @javax.annotation.Resource(name = "aiCronExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor cronExecutor;

    private static final Pattern EVERY = Pattern.compile("every(\\d+)([mhd])");

    @Scheduled(fixedDelay = 60000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        List<DnAiCronJob> due;
        try {
            due = cronMapper.selectList(new QueryWrapper<DnAiCronJob>()
                    .eq("enabled", 1)
                    .and(w -> w.isNull("next_run").or().le("next_run", now))
                    .orderByAsc("next_run").last("LIMIT 20"));
        } catch (Exception e) {
            log.warn("[cron] tick 取任务失败: {}", e.getMessage());
            return;
        }
        if (due == null || due.isEmpty()) return;
        for (DnAiCronJob job : due) {
            try { runJob(job, now); } catch (Exception e) { log.warn("[cron] 任务 {} 调度异常", job.getName(), e); }
        }
    }

    private void runJob(DnAiCronJob job, LocalDateTime now) {
        LocalDateTime next = computeNext(job.getScheduleCron(), now);
        if (next == null) {
            // 算不出下次: 置 error 不静默禁用(红线: 宁可报错也不静默停)
            cronMapper.update(null, new UpdateWrapper<DnAiCronJob>().eq("id", job.getId())
                    .set("last_status", "error:bad_schedule").set("updated_at", now));
            log.warn("[cron] 任务 {} schedule 无法解析: {}", job.getName(), job.getScheduleCron());
            return;
        }
        // 先推进 next_run(原子占行: 仅当 next_run 仍为旧值时推进), 抢到才执行 → 崩溃幂等至多一次
        UpdateWrapper<DnAiCronJob> claim = new UpdateWrapper<DnAiCronJob>()
                .eq("id", job.getId()).eq("enabled", 1)
                .set("next_run", next).set("updated_at", now);
        if (job.getNextRun() == null) claim.isNull("next_run"); else claim.eq("next_run", job.getNextRun());
        if (cronMapper.update(null, claim) == 0) return; // 已被并发领走, 跳过

        // 占行成功后异步执行(不阻塞 @Scheduled tick 线程, 防拖死全局调度器); 池满则丢, 下次 tick 再领
        final Long jobId = job.getId();
        final String jobName = job.getName(), prompt = job.getPrompt(), owner = job.getOwner();
        try {
        cronExecutor.execute(() -> {
            String status = "error", sid = null;
            try {
                AgentContext ctx = new AgentContext(owner, null, null, null, null);
                permResolver.resolveInto(ctx, owner);   // cron 以任务 owner 身份执行
                Map<String, Object> r = aiAgentService.runCron(prompt, ctx);
                status = r == null ? "error" : String.valueOf(r.get("status"));
                sid = r == null ? null : String.valueOf(r.get("sessionId"));
            } catch (Exception e) {
                status = cap("error:" + e.getMessage(), 60);
                log.warn("[cron] 任务 {} 执行异常", jobName, e);
            }
            cronMapper.update(null, new UpdateWrapper<DnAiCronJob>().eq("id", jobId)
                    .set("last_run", LocalDateTime.now()).set("last_status", cap(status, 60)).set("last_session_id", sid)
                    .setSql("run_count = run_count + 1").set("updated_at", LocalDateTime.now()));
            log.info("[cron] 任务 {} 执行完 status={} session={} next={}", jobName, status, sid, next);
        });
        } catch (java.util.concurrent.RejectedExecutionException rex) { // 池满: 标记可见, 不抛断 tick; 下周期 next_run 到点再跑
            log.warn("[cron] 线程池忙跳过本次 job={}, 下周期重试", jobName);
            try { cronMapper.update(null, new UpdateWrapper<DnAiCronJob>().eq("id", jobId)
                    .set("last_status", "error:pool_busy").set("updated_at", LocalDateTime.now())); } catch (Exception ignore) {}
        }
    }

    /** schedule → 下次执行时间。支持 everyNm/everyNh/everyNd 与 Spring 6 段 cron。无法解析返 null。 */
    public static LocalDateTime computeNext(String schedule, LocalDateTime from) {
        if (schedule == null || schedule.trim().isEmpty()) return null;
        String s = schedule.trim().toLowerCase();
        try {
            if (s.startsWith("every")) {
                Matcher m = EVERY.matcher(s);
                if (!m.matches()) return null;
                int n = Integer.parseInt(m.group(1));
                if (n <= 0) return null;
                switch (m.group(2)) {
                    case "m": return from.plusMinutes(n);
                    case "h": return from.plusHours(n);
                    case "d": return from.plusDays(n);
                    default: return null;
                }
            }
            return CronExpression.parse(schedule.trim()).next(from);
        } catch (Exception e) {
            return null;
        }
    }

    private static String cap(String s, int m) {
        return s == null ? null : (s.length() > m ? s.substring(0, m) : s);
    }
}
