package com.datanote.sync.service;

import com.datanote.domain.orchestration.TaskSchedulerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnSyncJobDependencyMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnSyncJobDependency;
import com.datanote.model.DnTaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
    private final DnSyncJobDependencyMapper dependencyMapper;
    private final DnTaskExecutionMapper taskExecutionMapper;

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
                    // DAG 依赖门控：上游今日未全部 SUCCESS 则等待，不更新 lastFireMap，下个 tick 再判
                    if (!depsReady(jobId)) {
                        log.info("[SyncJobScheduler] 等待上游就绪，暂不触发 jobId={}", jobId);
                        continue;
                    }
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
     * DAG 依赖门控：查该 job 的上游，对每个上游取今日（>= 当天 00:00）最新 DbSync 执行状态，
     * 全部 SUCCESS 才就绪。无上游恒就绪。
     */
    private boolean depsReady(Long jobId) {
        List<DnSyncJobDependency> deps = dependencyMapper.selectList(
                new LambdaQueryWrapper<DnSyncJobDependency>()
                        .eq(DnSyncJobDependency::getSyncJobId, jobId));
        if (deps == null || deps.isEmpty()) {
            return true;
        }
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<Long> upstreamIds = new ArrayList<>();
        Map<Long, String> latestStatus = new HashMap<>();
        for (DnSyncJobDependency d : deps) {
            Long up = d.getUpstreamSyncJobId();
            upstreamIds.add(up);
            DnTaskExecution last = taskExecutionMapper.selectOne(
                    new LambdaQueryWrapper<DnTaskExecution>()
                            .eq(DnTaskExecution::getSyncTaskId, up)
                            .eq(DnTaskExecution::getTaskType, "DbSync")
                            .ge(DnTaskExecution::getStartTime, todayStart)
                            .orderByDesc(DnTaskExecution::getId)
                            .last("LIMIT 1"));
            if (last != null) {
                latestStatus.put(up, last.getStatus());
            }
        }
        return DepReadyChecker.allReady(upstreamIds, latestStatus);
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
