package com.datanote.domain.integration.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.domain.integration.mapper.DnSyncErrorRowMapper;
import com.datanote.domain.integration.mapper.DnSyncFolderMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.integration.model.DnSyncErrorRow;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.common.model.R;
import com.datanote.domain.integration.service.SyncJobExecutor;
import com.datanote.domain.integration.service.SyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 关系库同步任务 Controller。
 */
@Slf4j
@RestController
@RequestMapping("/api/sync-job")
@Tag(name = "数据同步", description = "数据库之间的同步任务管理与执行")
@RequiredArgsConstructor
public class SyncJobController {

    private final SyncJobService syncJobService;
    private final SyncJobExecutor syncJobExecutor;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnSyncJobMapper syncJobMapper;
    private final DnSyncFolderMapper folderMapper;
    private final com.datanote.domain.integration.mapper.DnSyncJobAuditMapper auditMapper;
    private final com.datanote.domain.integration.service.DataReconciliationService reconciliationService;
    private final com.datanote.domain.integration.service.CdcEngineManager cdcEngineManager;
    private final DnSyncErrorRowMapper syncErrorRowMapper;
    private final com.datanote.domain.integration.mapper.DnCdcDeadLetterMapper cdcDeadLetterMapper;   // 全站#21 CDC死信合并展示
    private final com.datanote.domain.integration.schema.TableSchemaService tableSchemaService;
    private final com.datanote.domain.integration.schema.SchemaDriftService schemaDriftService;

    @Operation(summary = "任务列表")
    @GetMapping("/list")
    public R<List<DnSyncJob>> list() {
        return R.ok(syncJobService.list());
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public R<DnSyncJob> getById(@PathVariable Long id) {
        return R.ok(syncJobService.getById(id));
    }

    @Operation(summary = "保存任务")
    @PostMapping("/save")
    public R<DnSyncJob> save(@RequestBody DnSyncJob job) {
        try {
            return R.ok(syncJobService.save(job));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "同步前预检（连通性/源表主键/目标库/cron）")
    @PostMapping("/precheck")
    public R<java.util.Map<String, Object>> precheck(@RequestBody DnSyncJob job) {
        return R.ok(syncJobService.precheck(job));
    }

    @Operation(summary = "删除任务")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        syncJobService.delete(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "坏行(DLQ)列表(全站#21: 合并 CDC 死信, stage=CDC)")
    @GetMapping("/{id}/error-rows")
    public R<List<DnSyncErrorRow>> errorRows(@PathVariable Long id,
                                             @RequestParam(defaultValue = "200") int limit) {
        int n = Math.min(Math.max(limit, 1), 1000);
        List<DnSyncErrorRow> rows = syncErrorRowMapper.selectList(
                new LambdaQueryWrapper<DnSyncErrorRow>()
                        .eq(DnSyncErrorRow::getJobId, id)
                        .orderByDesc(DnSyncErrorRow::getId)
                        .last("limit " + n));
        if (rows == null) rows = new java.util.ArrayList<>();
        // 全站#21: CDC 死信原先只写 dn_cdc_dead_letter 无任何展示口——映射进同一坏行视图
        List<com.datanote.domain.integration.model.DnCdcDeadLetter> dead = cdcDeadLetterMapper.selectList(
                new LambdaQueryWrapper<com.datanote.domain.integration.model.DnCdcDeadLetter>()
                        .eq(com.datanote.domain.integration.model.DnCdcDeadLetter::getJobId, id)
                        .orderByDesc(com.datanote.domain.integration.model.DnCdcDeadLetter::getId)
                        .last("limit " + n));
        if (dead != null) {
            for (com.datanote.domain.integration.model.DnCdcDeadLetter d : dead) {
                if (d == null) continue;
                DnSyncErrorRow r = new DnSyncErrorRow();
                r.setId(d.getId());
                r.setJobId(d.getJobId());
                r.setSourceTable((d.getSourceDb() == null ? "" : d.getSourceDb() + ".") + (d.getSourceTable() == null ? "" : d.getSourceTable()));
                r.setRawRow(d.getOriginValue());
                r.setErrorCode(d.getErrorType());
                r.setErrorMsg(d.getErrorReason());
                r.setStage("CDC");
                r.setCreatedAt(d.getCreatedAt());
                rows.add(r);
            }
            rows.sort((a, b) -> {
                if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                if (a.getCreatedAt() == null) return 1;
                if (b.getCreatedAt() == null) return -1;
                return b.getCreatedAt().compareTo(a.getCreatedAt());
            });
            if (rows.size() > n) rows = new java.util.ArrayList<>(rows.subList(0, n));
        }
        return R.ok(rows);
    }

    @Operation(summary = "清空坏行(DLQ, 含 CDC 死信)")
    @DeleteMapping("/{id}/error-rows")
    public R<String> clearErrorRows(@PathVariable Long id) {
        int del = syncErrorRowMapper.delete(
                new LambdaQueryWrapper<DnSyncErrorRow>().eq(DnSyncErrorRow::getJobId, id));
        int delCdc = cdcDeadLetterMapper.delete(
                new LambdaQueryWrapper<com.datanote.domain.integration.model.DnCdcDeadLetter>()
                        .eq(com.datanote.domain.integration.model.DnCdcDeadLetter::getJobId, id));
        return R.ok("已清空 " + (del + delCdc) + " 条" + (delCdc > 0 ? "(含 CDC 死信 " + delCdc + " 条)" : ""));
    }

    @Operation(summary = "样本预览(前N行)")
    @GetMapping("/preview")
    public R<java.util.Map<String, Object>> preview(@RequestParam Long dsId, @RequestParam String db,
                                                    @RequestParam String table,
                                                    @RequestParam(defaultValue = "20") int limit) {
        int n = Math.min(Math.max(limit, 1), 200);
        try {
            com.datanote.domain.integration.connector.DbConnector c = syncJobService.buildConnector(dsId, db);
            String sql = "SELECT * FROM " + com.datanote.domain.integration.util.SqlIdentifiers.quote(db) + "."
                    + com.datanote.domain.integration.util.SqlIdentifiers.quote(table) + " LIMIT " + n;
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            try (java.sql.Connection conn = c.getConnection();
                 java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(sql)) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int cc = md.getColumnCount();
                java.util.List<String> cols = new java.util.ArrayList<>();
                String[] maskTypes = new String[cc];   // 按列名命中敏感类型则脱敏(防原始预览泄露明文 PII)
                for (int i = 1; i <= cc; i++) {
                    String label = md.getColumnLabel(i);
                    cols.add(label);
                    maskTypes[i - 1] = sensitiveMaskType(label);
                }
                java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    java.util.List<Object> row = new java.util.ArrayList<>();
                    for (int i = 1; i <= cc; i++) {
                        Object v = rs.getObject(i);
                        String s = (v == null) ? null : String.valueOf(v);
                        if (s != null && maskTypes[i - 1] != null) {
                            s = String.valueOf(com.datanote.domain.integration.util.PiiMasker.mask(s, maskTypes[i - 1], null));
                        }
                        row.add(s);
                    }
                    rows.add(row);
                }
                r.put("columns", cols);
                r.put("rows", rows);
            }
            return R.ok(r);
        } catch (Exception e) {
            return R.fail("样本预览失败: " + e.getMessage());
        }
    }

    /** 按列名命中常见敏感字段, 返回 PiiMasker 脱敏类型; 未命中返回 null(不脱敏)。 */
    private static String sensitiveMaskType(String columnName) {
        if (columnName == null) return null;
        String n = columnName.toLowerCase();
        if (n.contains("phone") || n.contains("mobile") || n.contains("tel")
                || n.contains("手机") || n.contains("电话")) return "PHONE";
        if (n.contains("email") || n.contains("mail") || n.contains("邮箱")) return "EMAIL";
        if (n.contains("idcard") || n.contains("id_card") || n.contains("identity")
                || n.contains("身份证")) return "IDCARD";
        if (n.contains("password") || n.contains("passwd") || n.contains("pwd")
                || n.contains("secret") || n.contains("token") || n.contains("密码")) return "REDACT";
        return null;
    }

    @Operation(summary = "按正则匹配源库表名(整库/批量/分表汇聚配置生成用)")
    @GetMapping("/match-tables")
    public R<java.util.List<String>> matchTables(@RequestParam Long dsId, @RequestParam String db,
                                                 @RequestParam(required = false) String include,
                                                 @RequestParam(required = false) String exclude) {
        // 正则长度护栏：限制用户正则长度，缓解 ReDoS（叠加鉴权 + 表名短，风险可控）
        if ((include != null && include.length() > 200) || (exclude != null && exclude.length() > 200)) {
            return R.fail("正则表达式过长（>200 字符）");
        }
        try {
            com.datanote.domain.integration.connector.DbConnector c = syncJobService.buildConnector(dsId, db);
            java.util.List<String> all = c.listTables(db);
            return R.ok(com.datanote.domain.integration.util.RegexTableMatcher.match(all, include, exclude));
        } catch (java.util.regex.PatternSyntaxException e) {
            return R.fail("正则非法: " + e.getMessage());
        } catch (Exception e) {
            return R.fail("匹配失败: " + e.getMessage());
        }
    }

    @Operation(summary = "重置源表schema快照(人工确认危险漂移后恢复同步)")
    @DeleteMapping("/{id}/schema-snapshot")
    public R<String> resetSchemaSnapshot(@PathVariable Long id, @RequestParam String table) {
        int n = schemaDriftService.reset(id, table);
        return R.ok("已重置 " + n + " 条快照，下次运行将以当前 schema 重建基线");
    }

    @Operation(summary = "目标建表DDL预览")
    @PostMapping("/ddl-preview")
    public R<java.util.Map<String, Object>> ddlPreview(@RequestBody java.util.Map<String, Object> req) {
        try {
            Long srcDsId = Long.valueOf(String.valueOf(req.get("srcDsId")));
            String srcDb = req.get("srcDb") == null ? "" : String.valueOf(req.get("srcDb"));
            String srcTable = req.get("srcTable") == null ? "" : String.valueOf(req.get("srcTable"));
            if (srcDb.isEmpty() || srcTable.isEmpty()) { return R.fail("srcDb/srcTable 不能为空"); }
            Long tgtDsId = Long.valueOf(String.valueOf(req.get("tgtDsId")));
            String tgtDb = req.get("tgtDb") == null || String.valueOf(req.get("tgtDb")).isEmpty()
                    ? srcDb : String.valueOf(req.get("tgtDb"));
            String tgtTable = req.get("tgtTable") == null || String.valueOf(req.get("tgtTable")).isEmpty()
                    ? srcTable : String.valueOf(req.get("tgtTable"));
            com.datanote.domain.integration.connector.DbConnector src = syncJobService.buildConnector(srcDsId, srcDb);
            com.datanote.domain.integration.connector.DbConnector tgt = syncJobService.buildConnector(tgtDsId, tgtDb);
            java.util.List<com.datanote.domain.integration.connector.ColumnDef> cols = src.getColumnDefs(srcDb, srcTable);
            String ddl = tableSchemaService.buildDdl(tgt.getDatabaseType(), tgtDb, tgtTable, cols);
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("ddl", ddl);
            return R.ok(r);
        } catch (Exception e) {
            return R.fail("DDL预览失败: " + e.getMessage());
        }
    }

    @Operation(summary = "手动运行（全量）")
    @PostMapping("/{id}/run")
    public R<Long> run(@PathVariable Long id) {
        try {
            Long execId = syncJobExecutor.run(id, "manual");
            return R.ok(execId);
        } catch (Exception e) {
            log.error("运行同步任务失败: id={}", id, e);
            return R.fail("运行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "停止运行中的任务（全量/增量）")
    @PostMapping("/{id}/stop")
    public R<String> stop(@PathVariable Long id) {
        boolean hit = syncJobExecutor.stop(id);
        return hit ? R.ok("已请求停止") : R.fail("任务当前不在运行中");
    }

    @Operation(summary = "移动任务到文件夹")
    @PostMapping("/{id}/move")
    public R<String> move(@PathVariable Long id, @RequestParam Long folderId) {
        if (folderId == null || folderId < 0) {
            return R.fail("目标文件夹非法");
        }
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
        }
        // folderId==0 表示根目录，直接放行；否则校验文件夹是否存在
        if (folderId != 0) {
            if (folderMapper.selectById(folderId) == null) {
                return R.fail("目标文件夹不存在");
            }
        }
        job.setFolderId(folderId);
        job.setUpdatedAt(java.time.LocalDateTime.now());
        syncJobMapper.updateById(job);
        return R.ok("已移动");
    }

    @Operation(summary = "执行历史")
    @GetMapping("/{id}/executions")
    public R<List<DnTaskExecution>> executions(@PathVariable Long id) {
        LambdaQueryWrapper<DnTaskExecution> wrapper = new LambdaQueryWrapper<DnTaskExecution>()
                .eq(DnTaskExecution::getSyncTaskId, id)
                .eq(DnTaskExecution::getTaskType, "DbSync")
                .orderByDesc(DnTaskExecution::getId)
                .last("LIMIT 50");
        return R.ok(taskExecutionMapper.selectList(wrapper));
    }

    @Operation(summary = "操作审计历史")
    @GetMapping("/{id}/audit")
    public R<List<com.datanote.domain.integration.model.DnSyncJobAudit>> audit(@PathVariable Long id) {
        return R.ok(auditMapper.selectList(new LambdaQueryWrapper<com.datanote.domain.integration.model.DnSyncJobAudit>()
            .eq(com.datanote.domain.integration.model.DnSyncJobAudit::getJobId, id)
            .orderByDesc(com.datanote.domain.integration.model.DnSyncJobAudit::getId).last("LIMIT 100")));
    }

    @Operation(summary = "启用定时调度")
    @PostMapping("/{id}/online")
    public R<String> online(@PathVariable Long id) {
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
        }
        job.setScheduleStatus("online");
        syncJobMapper.updateById(job);
        return R.ok("已启用定时调度");
    }

    @Operation(summary = "停用定时调度")
    @PostMapping("/{id}/offline")
    public R<String> offline(@PathVariable Long id) {
        DnSyncJob job = syncJobMapper.selectById(id);
        if (job == null) {
            return R.fail("任务不存在: " + id);
        }
        job.setScheduleStatus("offline");
        syncJobMapper.updateById(job);
        return R.ok("已停用定时调度");
    }

    // ===== M3c：行数对账 =====

    @Operation(summary = "行数对账(源/目标 count 比对)")
    @PostMapping("/{id}/reconcile")
    public R<java.util.Map<String, Object>> reconcile(@PathVariable Long id) {
        try {
            return R.ok(reconciliationService.reconcile(id));
        } catch (Exception e) {
            log.error("对账失败: id={}", id, e);
            return R.fail("对账失败: " + e.getMessage());
        }
    }

    @Operation(summary = "分片checksum深度对账")
    @PostMapping("/{id}/checksum")
    public R<java.util.Map<String, Object>> checksum(@PathVariable Long id) {
        try { return R.ok(reconciliationService.checksum(id)); }
        catch (Exception e) { return R.fail("checksum对账失败: " + e.getMessage()); }
    }

    // ===== M3c：监控大盘（放 Controller 避免 SyncJobService 注入 CdcEngineManager 成环） =====

    @Operation(summary = "监控大盘(所有任务状态+最新计数+CDC指标)")
    @GetMapping("/dashboard")
    public R<java.util.List<java.util.Map<String, Object>>> dashboard() {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        for (DnSyncJob job : syncJobService.list()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", job.getId());
            m.put("jobName", job.getJobName());
            m.put("syncMode", job.getSyncMode());
            m.put("status", job.getStatus());
            m.put("scheduleStatus", job.getScheduleStatus());
            DnTaskExecution last = taskExecutionMapper.selectOne(new LambdaQueryWrapper<DnTaskExecution>()
                    .eq(DnTaskExecution::getSyncTaskId, job.getId())
                    .eq(DnTaskExecution::getTaskType, "DbSync")
                    .orderByDesc(DnTaskExecution::getId).last("LIMIT 1"));
            if (last != null) {
                m.put("lastStatus", last.getStatus());
                m.put("lastReadCount", last.getReadCount());
                m.put("lastWriteCount", last.getWriteCount());
                m.put("lastErrorCount", last.getErrorCount());
                m.put("lastTime", last.getStartTime());
            }
            if ("CDC".equalsIgnoreCase(job.getSyncMode())) {
                try {
                    m.putAll(prefixCdc(cdcEngineManager.metrics(job.getId())));
                } catch (Exception e) {
                    log.warn("CDC指标获取失败 jobId={}: {}", job.getId(), e.getMessage()); // 不影响整体大盘但留痕
                    m.put("cdcRunning", false);
                }
            }
            list.add(m);
        }
        return R.ok(list);
    }

    @Operation(summary = "大盘汇总(run级聚合:成功率/今日行数/DLQ/lag)")
    @GetMapping("/dashboard/summary")
    public R<java.util.Map<String, Object>> dashboardSummary() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        java.util.List<DnSyncJob> jobs = syncJobService.list();
        long running = 0, paused = 0, failed = 0;
        for (DnSyncJob j : jobs) {
            String st = j.getStatus();
            if ("RUNNING".equalsIgnoreCase(st)) running++;
            else if ("PAUSED".equalsIgnoreCase(st)) paused++;
            else if ("FAILED".equalsIgnoreCase(st)) failed++;
        }
        m.put("jobsTotal", jobs.size());
        m.put("running", running);
        m.put("paused", paused);
        m.put("failed", failed);
        // 近200次 DbSync 执行算成功率 + 今日执行聚合
        // 仅取聚合所需列（避免加载 LONGTEXT log 列）+ 限行，防大盘 5s 轮询拖垮 DB
        java.util.List<DnTaskExecution> recent = taskExecutionMapper.selectList(
                new LambdaQueryWrapper<DnTaskExecution>()
                        .select(DnTaskExecution::getStatus)
                        .eq(DnTaskExecution::getTaskType, "DbSync")
                        .orderByDesc(DnTaskExecution::getId).last("LIMIT 200"));
        java.time.LocalDateTime todayStart = java.time.LocalDate.now().atStartOfDay();
        java.util.List<DnTaskExecution> today = taskExecutionMapper.selectList(
                new LambdaQueryWrapper<DnTaskExecution>()
                        .select(DnTaskExecution::getStatus, DnTaskExecution::getWriteCount)
                        .eq(DnTaskExecution::getTaskType, "DbSync")
                        .ge(DnTaskExecution::getStartTime, todayStart).last("LIMIT 5000"));
        m.putAll(aggregateRuns(recent, today));
        long dlq = syncErrorRowMapper.selectCount(new LambdaQueryWrapper<DnSyncErrorRow>());
        m.put("dlqTotal", dlq);
        // CDC 最大延迟
        long maxLag = -1;
        for (DnSyncJob j : jobs) {
            if ("CDC".equalsIgnoreCase(j.getSyncMode())) {
                try {
                    Object lag = cdcEngineManager.metrics(j.getId()).get("lagMs");
                    if (lag instanceof Number) maxLag = Math.max(maxLag, ((Number) lag).longValue());
                } catch (Exception ignore) { /* 单任务指标失败不影响汇总 */ }
            }
        }
        m.put("maxCdcLagMs", maxLag < 0 ? null : maxLag);
        return R.ok(m);
    }

    /** 纯函数:近 N 次执行算成功率 + 今日执行聚合(写入行/成功/失败)。 */
    static java.util.Map<String, Object> aggregateRuns(java.util.List<DnTaskExecution> recent,
                                                       java.util.List<DnTaskExecution> today) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        long succ = 0;
        for (DnTaskExecution e : recent) if ("SUCCESS".equalsIgnoreCase(e.getStatus())) succ++;
        m.put("successRate", recent.isEmpty() ? null : (double) succ / recent.size());
        long rowsToday = 0, successToday = 0, failedToday = 0;
        for (DnTaskExecution e : today) {
            if (e.getWriteCount() != null) rowsToday += e.getWriteCount();
            if ("SUCCESS".equalsIgnoreCase(e.getStatus())) successToday++;
            else if ("FAILED".equalsIgnoreCase(e.getStatus())) failedToday++;
        }
        m.put("runsToday", (long) today.size());
        m.put("rowsToday", rowsToday);
        m.put("successToday", successToday);
        m.put("failedToday", failedToday);
        return m;
    }

    private static java.util.Map<String, Object> prefixCdc(java.util.Map<String, Object> cdc) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (cdc != null) {
            m.put("cdcRunning", cdc.get("running"));
            m.put("cdcLagMs", cdc.get("lagMs"));
            m.put("cdcEventsSeen", cdc.get("eventsSeen"));
        }
        return m;
    }

    // ===== M3c：断点管理（增量水位 + 全量 chunk） =====

    @Operation(summary = "查看断点(增量水位+chunk)")
    @GetMapping("/{id}/checkpoints")
    public R<java.util.Map<String, Object>> checkpoints(@PathVariable Long id) {
        return R.ok(syncJobService.getCheckpoints(id));
    }

    @Operation(summary = "重置增量水位")
    @PostMapping("/{id}/checkpoint/reset-incremental")
    public R<String> resetIncr(@PathVariable Long id, @RequestParam String table) {
        syncJobService.resetIncremental(id, table);
        return R.ok("已重置增量水位");
    }

    @Operation(summary = "重置全量chunk断点")
    @PostMapping("/{id}/checkpoint/reset-chunk")
    public R<String> resetChunk(@PathVariable Long id, @RequestParam String table) {
        syncJobService.clearChunkCursor(id, table);
        return R.ok("已重置chunk断点");
    }

    // ===== M4c：任务依赖（轻量 DAG） =====

    @Operation(summary = "查看上游依赖")
    @GetMapping("/{id}/dependencies")
    public R<List<com.datanote.domain.integration.model.DnSyncJobDependency>> dependencies(@PathVariable Long id) {
        return R.ok(syncJobService.listDependencies(id));
    }

    @Operation(summary = "添加上游依赖（自依赖/成环返回 fail）")
    @PostMapping("/{id}/dependencies")
    public R<String> addDependency(@PathVariable Long id, @RequestParam Long upstreamId) {
        try {
            syncJobService.addDependency(id, upstreamId);
            return R.ok("已添加上游依赖");
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "移除上游依赖")
    @DeleteMapping("/{id}/dependencies")
    public R<String> removeDependency(@PathVariable Long id, @RequestParam Long upstreamId) {
        syncJobService.removeDependency(id, upstreamId);
        return R.ok("已移除上游依赖");
    }

}
