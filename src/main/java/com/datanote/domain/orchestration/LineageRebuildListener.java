package com.datanote.domain.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 血缘自动重建监听器 — 同步任务保存/删除后异步重建 MAPPING 来源血缘边，
 * 另有夜间 02:40 兜底全量重建（错峰 01:00 元数据采集 / 01:30 健康分 / 02:10 指标）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineageRebuildListener {

    private final LineageEdgeService lineageEdgeService;

    /** 同步任务变更事件 → 异步重建血缘（失败仅告警，不影响任务保存/删除主流程） */
    @Async
    @EventListener
    public void onSyncJobChanged(SyncJobChangedEvent event) {
        Long jobId = event == null ? null : event.getJobId();
        try {
            int n = lineageEdgeService.rebuildFromSyncJobs();
            log.info("同步任务变更触发血缘重建 jobId={} 重建边数={}", jobId, n);
        } catch (Exception e) {
            log.warn("同步任务变更血缘重建失败 jobId={}: {}", jobId, e.getMessage());
        }
    }

    /** 夜间兜底全量重建：覆盖事件丢失/异步失败等场景 */
    @Scheduled(cron = "0 40 2 * * ?")
    public void nightlyRebuild() {
        try {
            int n = lineageEdgeService.rebuildFromSyncJobs();
            log.info("夜间兜底血缘重建完成, 重建边数={}", n);
        } catch (Exception e) {
            log.warn("夜间兜底血缘重建失败: {}", e.getMessage());
        }
    }
}
