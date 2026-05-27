package com.datanote.sync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.model.DnSyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 关系库同步定时调度器：独立于 TaskSchedulerService，按 cron 自动触发 FULL/INCREMENTAL 同步。
 * CDC 任务由 CdcEngineManager 常驻管理，不在此处处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncJobScheduler {

    private final DnSyncJobMapper syncJobMapper;
    private final SyncJobExecutor executor;

    /** 异步执行同步任务，避免阻塞调度线程 */
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    /** 记录每个 job 上次触发时间，防止同一 tick 内重复触发 */
    private final Map<Long, LocalDateTime> lastFireMap = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        log.info("[SyncJobScheduler] 关闭线程池...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 每 30 秒轮询一次，检查哪些 online 的 FULL/INCREMENTAL 任务需要触发。
     */
    @Scheduled(fixedDelay = 30000)
    public void tick() {
        List<DnSyncJob> jobs = syncJobMapper.selectList(
                new LambdaQueryWrapper<DnSyncJob>()
                        .eq(DnSyncJob::getScheduleStatus, "online")
                        .in(DnSyncJob::getSyncMode, Arrays.asList("FULL", "INCREMENTAL"))
        );

        if (jobs == null || jobs.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (DnSyncJob job : jobs) {
            try {
                String cron = job.getScheduleCron();
                if (cron == null || cron.trim().isEmpty()) {
                    continue;
                }

                Long jobId = job.getId();
                // 无记录时默认从 1 分钟前开始判断
                LocalDateTime lastFire = lastFireMap.getOrDefault(jobId, now.minusMinutes(1));

                if (shouldFire(cron, lastFire, now)) {
                    lastFireMap.put(jobId, now);
                    log.info("[SyncJobScheduler] 触发任务 jobId={}, cron={}, mode={}", jobId, cron, job.getSyncMode());
                    executorService.submit(() -> {
                        try {
                            executor.run(jobId, "schedule");
                        } catch (Exception e) {
                            log.error("[SyncJobScheduler] 任务执行失败: jobId={}", jobId, e);
                        }
                    });
                }
            } catch (Exception e) {
                log.error("[SyncJobScheduler] 处理 job={} 时异常，跳过", job.getId(), e);
            }
        }
    }

    /**
     * 判断 (lastFire, now] 区间内 cron 是否有触发点。可单独单测。
     *
     * @param cron     Spring cron 表达式（6 位，秒级）
     * @param lastFire 上次触发时间（不含）
     * @param now      当前时间（含）
     * @return true 表示区间内有触发点，需要执行
     */
    public static boolean shouldFire(String cron, LocalDateTime lastFire, LocalDateTime now) {
        if (cron == null || cron.trim().isEmpty()) {
            return false;
        }
        if (lastFire == null || now == null) {
            return false;
        }
        try {
            CronExpression expr = CronExpression.parse(cron.trim());
            // 从 lastFire 之后的第一个触发点
            LocalDateTime next = expr.next(lastFire);
            // 若触发点存在且 <= now，则说明区间内有触发
            return next != null && !next.isAfter(now);
        } catch (IllegalArgumentException e) {
            log.warn("[SyncJobScheduler] 无效 cron 表达式: {}", cron);
            return false;
        }
    }
}
