package com.datanote.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.integration.mapper.DnSyncErrorRowMapper;
import com.datanote.domain.orchestration.model.DnSchedulerRun;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.domain.integration.model.DnSyncErrorRow;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据清理服务 — 定时清理超过 30 天的执行记录
 */
@Service
@RequiredArgsConstructor
public class DataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupService.class);
    private static final int RETENTION_DAYS = 30;

    private final DnSchedulerRunMapper schedulerRunMapper;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnSyncErrorRowMapper syncErrorRowMapper;

    /**
     * 每天凌晨 2:00 清理超过 30 天的数据
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredData() {
        LocalDate cutoffDate = LocalDate.now().minusDays(RETENTION_DAYS);
        LocalDateTime cutoffDateTime = cutoffDate.atStartOfDay();

        // 各表清理相互独立：单表失败(锁超时等)不阻断其它表清理，本次失败的表下次再清
        int deletedRuns = 0, deletedExecs = 0, deletedErrs = 0;
        try {
            QueryWrapper<DnSchedulerRun> runQw = new QueryWrapper<>();
            runQw.lt("run_date", cutoffDate);
            deletedRuns = schedulerRunMapper.delete(runQw);
        } catch (Exception e) {
            log.error("清理 dn_scheduler_run 失败: {}", e.getMessage());
        }
        try {
            QueryWrapper<DnTaskExecution> execQw = new QueryWrapper<>();
            execQw.lt("created_at", cutoffDateTime);
            deletedExecs = taskExecutionMapper.delete(execQw);
        } catch (Exception e) {
            log.error("清理 dn_task_execution 失败: {}", e.getMessage());
        }
        try {
            // 坏行 DLQ 只增不删会膨胀，按同保留期清理
            QueryWrapper<DnSyncErrorRow> errQw = new QueryWrapper<>();
            errQw.lt("created_at", cutoffDateTime);
            deletedErrs = syncErrorRowMapper.delete(errQw);
        } catch (Exception e) {
            log.error("清理 dn_sync_error_row 失败: {}", e.getMessage());
        }

        if (deletedRuns > 0 || deletedExecs > 0 || deletedErrs > 0) {
            log.info("数据清理完成: 删除 {} 条调度记录, {} 条执行指标, {} 条同步坏行 (截止 {})",
                    deletedRuns, deletedExecs, deletedErrs, cutoffDate);
        }
    }
}
