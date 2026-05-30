package com.datanote.sync.service;

import com.alibaba.fastjson.JSON;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.util.FilterExpressionBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行数对账：每表两端 COUNT 比对（源叠加 filterExpression，目标不套过滤），写一条执行记录。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataReconciliationService {

    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;

    public Map<String, Object> reconcile(Long jobId) throws Exception {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在: " + jobId);
        }
        DbConnector src = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector tgt = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean allMatch = true;
        for (TableSyncConfig tc : syncJobService.parseTables(job)) {
            String ew = FilterExpressionBuilder.build(tc.getFilterExpression());
            long sc = count(src, job.getSourceDb(), tc.getSourceTable(), ew);
            // 目标不套源过滤表达式（列名可能不同）
            long tcnt = count(tgt, job.getTargetDb(), tc.getTargetTable(), null);
            boolean match = sc == tcnt;
            allMatch &= match;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", tc.getSourceTable() + "->" + tc.getTargetTable());
            m.put("sourceCount", sc);
            m.put("targetCount", tcnt);
            m.put("match", match);
            rows.add(m);
        }
        writeExec(jobId, allMatch, rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", allMatch);
        result.put("tables", rows);
        return result;
    }

    private long count(DbConnector conn, String db, String table, String ew) throws Exception {
        String sql = MysqlConnector.buildCountSql(db, table, ew);
        try (Connection c = conn.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void writeExec(Long jobId, boolean ok, List<Map<String, Object>> rows) {
        try {
            DnTaskExecution e = new DnTaskExecution();
            e.setSyncTaskId(jobId);
            e.setTaskType("DataReconciliation");
            e.setTriggerType("manual");
            e.setStatus(ok ? "SUCCESS" : "FAILED");
            e.setStartTime(LocalDateTime.now());
            e.setEndTime(LocalDateTime.now());
            e.setCreatedAt(LocalDateTime.now());
            e.setLog(JSON.toJSONString(rows));
            taskExecutionMapper.insert(e);
        } catch (Exception ex) {
            log.warn("对账执行记录失败 jobId={}", jobId, ex);
        }
    }
}
