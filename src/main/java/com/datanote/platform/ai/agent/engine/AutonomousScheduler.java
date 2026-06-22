package com.datanote.platform.ai.agent.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanote.platform.ai.agent.mapper.DnAiSessionMapper;
import com.datanote.platform.ai.agent.model.DnAiSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 无人值守自主执行调度(借鉴 CronScheduler 后台驱动范式)。每 ~12s tick 取需驱动的自主会话,
 * 原子心跳占行后异步交给 driveAutonomous 持续推进, 实现"批准一次→自主跑数小时到交付"。
 *
 * 健壮/红线:
 *  - 心跳占行(CAS last_heartbeat) → 防多实例/多线程重复领取同一会话。
 *  - 心跳过期(>STALE_MS, 即驱动线程崩溃/卡死)→ 下次 tick 重新领取续跑(断点续驱)。
 *  - 池满(AbortPolicy 抛 RejectedExecutionException)→ 回退心跳, 下次 tick 再领, 不丢任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomousScheduler {

    private final DnAiSessionMapper sessionMapper;
    private final AiAgentService aiAgentService;

    @javax.annotation.Resource(name = "aiAutonomousExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor autoExecutor;

    /** 心跳过期阈值(ms): 一个 run 周期墙钟最多 5min, 留余量到 10min 视为卡死可重领 */
    private static final long STALE_MS = 600_000L;

    @Scheduled(fixedDelay = 12000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(Duration.ofMillis(STALE_MS));
        List<DnAiSession> due;
        try {
            due = sessionMapper.selectList(new QueryWrapper<DnAiSession>()
                    .eq("autonomous", 1).eq("status", "running")
                    .and(w -> w.isNull("last_heartbeat").or().lt("last_heartbeat", staleBefore)) // 未在驱动 或 心跳过期
                    .orderByAsc("updated_at").last("LIMIT 10"));
        } catch (Exception e) {
            log.warn("[autonomous] tick 取任务失败: {}", e.getMessage());
            return;
        }
        if (due == null || due.isEmpty()) return;
        for (DnAiSession s : due) {
            // 原子占行: 仅当心跳仍为旧值(null/过期)时置 now, 抢到才驱动 → 防重复领取
            UpdateWrapper<DnAiSession> claim = new UpdateWrapper<DnAiSession>()
                    .eq("session_id", s.getSessionId()).eq("autonomous", 1).eq("status", "running")
                    .set("last_heartbeat", now).set("updated_at", now);
            if (s.getLastHeartbeat() == null) claim.isNull("last_heartbeat"); else claim.eq("last_heartbeat", s.getLastHeartbeat());
            if (sessionMapper.update(null, claim) == 0) continue; // 已被并发领走

            final String sid = s.getSessionId();
            final LocalDateTime prevHb = s.getLastHeartbeat();
            try {
                autoExecutor.execute(() -> {
                    try { aiAgentService.driveAutonomous(sid); }
                    catch (Exception e) { log.warn("[autonomous] 驱动 {} 异常", sid, e); }
                });
            } catch (Exception rejected) {
                // 池满: 回退心跳让下次 tick 重领(避免占着心跳却没人跑)
                sessionMapper.update(null, new UpdateWrapper<DnAiSession>().eq("session_id", sid)
                        .set("last_heartbeat", prevHb).set("updated_at", LocalDateTime.now()));
            }
        }
    }
}
