package com.datanote.domain.orchestration;

import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.domain.orchestration.model.DnSchedulerRun;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.common.LogBroadcastService;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.domain.integration.DataxService;
import com.datanote.domain.integration.HiveService;
import com.datanote.common.Constants;
import com.datanote.common.util.CryptoUtil;
import com.datanote.common.util.SecretRedactor;
import com.datanote.domain.orchestration.util.ProcessUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * 任务执行服务 — 负责脚本/同步任务的实际执行、超时控制、失败处理和重试
 */
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final DnScriptMapper scriptMapper;
    private final DnSyncTaskMapper syncTaskMapper;
    private final DnSchedulerRunMapper runMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final HiveService hiveService;
    private final DataxService dataxService;
    private final MetadataService metadataService;
    private final TaskDependencyService taskDependencyService;
    private final LogBroadcastService logBroadcastService;
    private final TaskSchedulerService taskSchedulerService;
    private final com.datanote.platform.notify.NotificationService notificationService;   // 全站#25 调度终败通知

    @Value("${datax.job-dir}")
    private String jobDir;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    @Value("${datanote.task.shell-enabled:false}")
    private boolean shellEnabled;

    // 大数据调度优化: Doris 并行度(0=不设, 默认8提升亿级聚合/扫描); 调度SQL查询超时秒(0=不限, 防失控)
    @Value("${datanote.schedule.doris-parallel:8}")
    private int dorisParallel;

    // 深度调优(DWD/DWS/ADS 跑大表): 额外 Doris 会话变量, 逗号分隔 "k=v"(如 exec_mem_limit=8589934592,parallel_pipeline_task_num=8),
    // 在每条脚本SQL同连接守卫式 SET(版本不支持自动忽略不破坏)。按集群算力调, 默认空。
    @Value("${datanote.schedule.doris-session-vars:}")
    private String dorisSessionVars;

    // ODS 抽取(DataX)自适应: 基础批次/并发, 与大表(>千万/>亿)放大值; 大表减写往返+并行抽取
    @Value("${datanote.datax.batch-size:2048}") private int dataxBatch;
    @Value("${datanote.datax.channel:3}") private int dataxChannel;
    @Value("${datanote.datax.big-batch-size:8192}") private int dataxBigBatch;
    @Value("${datanote.datax.big-channel:6}") private int dataxBigChannel;

    // 指数退避参数
    private static final long RETRY_BASE_MS = 5000;    // 5 秒
    private static final long RETRY_MAX_MS = 300000;   // 5 分钟

    // 重试延迟调度: 专用调度池(2 daemon线程), 避免在工作线程里 Thread.sleep 占满主池(重试风暴致新任务无线程)
    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "task-retry-sched"); t.setDaemon(true); return t;
            });

    // 引用全局常量
    private static final int MAX_LOG_SIZE = Constants.MAX_LOG_SIZE;

    // ======================== 带超时的任务执行 ========================

    /**
     * 带超时的任务执行
     */
    public void executeTaskWithTimeout(DnSchedulerRun run) {
        if (run == null) {
            throw new BusinessException("执行任务失败：运行记录为空");
        }
        if (run.getTaskId() == null || run.getTaskType() == null) {
            throw new BusinessException("执行任务失败：缺少 taskId 或 taskType");
        }
        if (run.getRunDate() == null) {
            throw new BusinessException("执行任务失败：缺少运行日期 runDate");
        }

        // 获取超时配置
        int timeoutSeconds = getTaskTimeout(run);

        log.info("开始执行任务: {} {} (runDate={}, timeout={}s)",
                run.getTaskType(), run.getTaskId(), run.getRunDate(), timeoutSeconds);

        logBroadcastService.broadcastTaskLog(run.getTaskId(), run.getTaskType(), "INFO",
                "开始执行任务 (timeout=" + timeoutSeconds + "s)");

        // 更新状态为 RUNNING
        run.setStatus(DnSchedulerRun.STATUS_RUNNING);
        run.setStartTime(LocalDateTime.now());
        runMapper.updateById(run);
        logBroadcastService.broadcastStatusChange(run.getTaskId(), run.getTaskType(),
                DnSchedulerRun.STATUS_RUNNING, run.getRunDate().toString());

        StringBuilder logBuilder = new StringBuilder();
        String bizdate = run.getRunDate().format(DateTimeFormatter.ISO_LOCAL_DATE);

        // 使用 Future 实现超时控制（复用共享线程池）
        ExecutorService timeoutExecutor = taskSchedulerService.getTimeoutExecutor();
        Future<?> future = timeoutExecutor.submit(() -> {
            try {
                if (Constants.TASK_TYPE_SYNC_TASK.equals(run.getTaskType())) {
                    executeSyncTask(run.getTaskId(), bizdate, logBuilder);
                } else {
                    executeScript(run.getTaskId(), bizdate, logBuilder);
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);

            // 写最终成功前重查 DB 状态：若运行期间被手动停止(stopTask 置 FAILED)，不覆盖其结果，也不触发下游
            DnSchedulerRun curRun = runMapper.selectById(run.getId());
            if (curRun != null && curRun.getStatus() != null && curRun.getStatus() == DnSchedulerRun.STATUS_FAILED) {
                log.info("任务已被手动停止，跳过成功状态覆盖: {} {} (runDate={})",
                        run.getTaskType(), run.getTaskId(), run.getRunDate());
                return;
            }

            // 执行成功
            run.setStatus(DnSchedulerRun.STATUS_SUCCESS);
            run.setEndTime(LocalDateTime.now());
            run.setLog(truncateLog(logBuilder));
            runMapper.updateById(run);
            log.info("任务执行成功: {} {} (runDate={})", run.getTaskType(), run.getTaskId(), run.getRunDate());

            // 落盘结构化执行指标
            saveTaskExecution(run, "SUCCESS", logBuilder.toString());
            logBroadcastService.broadcastTaskLog(run.getTaskId(), run.getTaskType(), "INFO", "任务执行成功");
            logBroadcastService.broadcastStatusChange(run.getTaskId(), run.getTaskType(),
                    DnSchedulerRun.STATUS_SUCCESS, run.getRunDate().toString());

            // 恢复被暂停的下游任务（设为 WAITING，让依赖检查决定是否执行）
            taskDependencyService.resumeDownstreamAfterSuccess(
                    run.getTaskId(), run.getTaskType(), run.getRunDate(), run.getRunType());

            // 触发下游检查
            taskSchedulerService.processWaitingTasks(run.getRunDate(), run.getRunType());

        } catch (TimeoutException e) {
            future.cancel(true);
            logBuilder.append("\n[TIMEOUT] 任务执行超时（").append(timeoutSeconds).append("秒），已强制终止");
            handleTaskFailure(run, logBuilder, "任务执行超时");

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String errorMsg = cause != null ? cause.getMessage() : e.getMessage();
            logBuilder.append("\n[ERROR] ").append(SecretRedactor.redact(errorMsg));
            handleTaskFailure(run, logBuilder, errorMsg);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logBuilder.append("\n[INTERRUPTED] 任务被中断");
            handleTaskFailure(run, logBuilder, "任务被中断");

        } finally {
            // 共享线程池不需要 shutdown
        }
    }

    // ======================== 失败处理与重试 ========================

    /**
     * 处理任务失败：记录状态，判断是否自动重试
     */
    private void handleTaskFailure(DnSchedulerRun run, StringBuilder logBuilder, String errorMsg) {
        log.error("任务执行失败: {} {} - {}", run.getTaskType(), run.getTaskId(), SecretRedactor.redact(errorMsg));

        // 重查 DB 状态：若已被手动停止(stopTask 置 FAILED)，不再重试/覆盖，保留手动停止结果
        DnSchedulerRun curRun = runMapper.selectById(run.getId());
        if (curRun != null && curRun.getStatus() != null && curRun.getStatus() == DnSchedulerRun.STATUS_FAILED) {
            log.info("任务已被手动停止，跳过失败重试与状态覆盖: {} {}", run.getTaskType(), run.getTaskId());
            return;
        }

        int retryCount = run.getRetryCount() != null ? run.getRetryCount() : 0;
        int maxRetries = getTaskMaxRetries(run);

        if (retryCount < maxRetries) {
            // 自动重试（指数退避）
            long delayMs = calculateRetryDelay(retryCount);
            logBuilder.append("\n[RETRY] 第 ").append(retryCount + 1).append("/").append(maxRetries)
                      .append(" 次重试，").append(delayMs / 1000).append("秒后执行");

            run.setStatus(DnSchedulerRun.STATUS_WAITING);
            run.setRetryCount(retryCount + 1);
            run.setEndTime(LocalDateTime.now());
            run.setLog(truncateLog(logBuilder));
            runMapper.updateById(run);

            // 延迟后重新触发: 用专用调度池延时, 不占工作线程(原 submit+sleep 会占满主池, 重试风暴时新任务饿死)
            retryScheduler.schedule(
                    () -> taskSchedulerService.processWaitingTasks(run.getRunDate(), run.getRunType()),
                    delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            // 达到最大重试次数，标记为失败
            if (maxRetries > 0) {
                logBuilder.append("\n[FAILED] 已达到最大重试次数（").append(maxRetries).append("），任务失败");
            }
            run.setStatus(DnSchedulerRun.STATUS_FAILED);
            run.setEndTime(LocalDateTime.now());
            run.setLog(truncateLog(logBuilder));
            runMapper.updateById(run);

            // 落盘结构化执行指标
            saveTaskExecution(run, "FAILED", logBuilder.toString());

            // 自动暂停所有下游任务
            int paused = taskDependencyService.pauseDownstream(run.getTaskId(), run.getTaskType(),
                    run.getRunDate(), run.getRunType());
            if (paused > 0) {
                log.info("任务 {}:{} 失败，已自动暂停 {} 个下游任务",
                        run.getTaskType(), run.getTaskId(), paused);
            }

            // 全站#25: 终败(重试耗尽)通知任务创建人, 回退 admin; 铃铛深链运维中心
            try {
                String taskName = run.getTaskType() + "#" + run.getTaskId();
                String receiver = null;
                // 接收人优先级: 显式配置的告警联系人(alertContact) → 任务创建人 → admin 兜底
                if (Constants.TASK_TYPE_SCRIPT.equals(run.getTaskType())) {
                    DnScript s = scriptMapper.selectById(run.getTaskId());
                    if (s != null) {
                        taskName = s.getScriptName();
                        receiver = (s.getAlertContact() != null && !s.getAlertContact().trim().isEmpty())
                                ? s.getAlertContact().trim() : s.getCreatedBy();
                    }
                } else {
                    DnSyncTask t = syncTaskMapper.selectById(run.getTaskId());
                    if (t != null) {
                        taskName = t.getTaskName();
                        receiver = (t.getAlertContact() != null && !t.getAlertContact().trim().isEmpty())
                                ? t.getAlertContact().trim() : t.getCreatedBy();
                    }
                }
                if (receiver == null || receiver.trim().isEmpty()) receiver = "admin";
                notificationService.notify(receiver, "SCHED_FAILED",
                        "调度任务失败: " + taskName + " (数据日期 " + run.getRunDate() + ")",
                        "operations", run.getId(), null);
            } catch (Exception ne) {
                log.warn("调度失败通知发送失败, runId={}", run.getId(), ne);
            }
        }
    }

    // ======================== 具体执行逻辑 ========================

    private void executeSyncTask(Long taskId, String bizdate, StringBuilder logBuilder) throws Exception {
        DnSyncTask task = syncTaskMapper.selectById(taskId);
        if (task == null) throw new RuntimeException("同步任务不存在: " + taskId);

        logBuilder.append("[").append(nowTime()).append("] 开始执行同步任务: ").append(task.getTaskName()).append("\n");
        logBuilder.append("源表: ").append(task.getSourceDb()).append(".").append(task.getSourceTable()).append("\n");
        logBuilder.append("目标表: ods.").append(task.getTargetTable()).append("\n");
        logBuilder.append("数据日期: ").append(bizdate).append("\n\n");

        DnDatasource ds = resolveDatasource(task);
        if (ds == null) throw new RuntimeException("数据源不存在: " + task.getSourceDsId());
        String syncMode = task.getSyncMode() != null ? task.getSyncMode() : "df";
        String targetTable = "ods." + task.getTargetTable();
        List<ColumnInfo> columns;

        // ========== 第一步：预检（任一失败直接中止） ==========

        // 1.1 检查 MySQL 源库连通性
        logBuilder.append("[预检] MySQL 源库连接...");
        try {
            columns = getSourceColumns(ds, task);
            if (columns == null || columns.isEmpty()) {
                throw new RuntimeException("source table columns not found");
            }
            logBuilder.append(" 正常\n");
        } catch (Exception e) {
            logBuilder.append(" 失败: ").append(e.getMessage()).append("\n");
            throw new RuntimeException("MySQL 源库连接失败: " + e.getMessage());
        }

        // 1.2 确保 Doris 表存在
        logBuilder.append("[预检] Doris 表...");
        try {
            String ddl = hiveService.generateDDL(task.getSourceDb(), task.getSourceTable(), columns, syncMode);
            hiveService.executeDDL(ddl);
            logBuilder.append(" 就绪\n");
        } catch (Exception e) {
            logBuilder.append(" 失败: ").append(e.getMessage()).append("\n");
            throw new RuntimeException("Doris 表创建失败: " + e.getMessage());
        }

        // ========== 第二步：生成 DataX 配置（每次动态生成，确保日期和列是最新的） ==========

        logBuilder.append("[DataX] 生成配置...\n");
        // 大表自适应: 估源表行数(information_schema 即时近似), >亿/>千万 放大批次与并发, 提升 ODS 抽取吞吐
        int dxBatch = dataxBatch, dxChannel = dataxChannel;
        long srcEst = metadataService.estimateRowsByConnection(ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(),
                task.getSourceDb(), task.getSourceTable());
        if (srcEst >= 100_000_000L) {
            dxBatch = dataxBigBatch; dxChannel = dataxBigChannel;
            logBuilder.append("[大表优化] 源表约 ").append(srcEst).append(" 行(>1亿): DataX 批次=").append(dxBatch).append(", 并发=").append(dxChannel).append("\n");
        } else if (srcEst >= 10_000_000L) {
            dxBatch = Math.max(dataxBatch, 4096); dxChannel = Math.max(dataxChannel, 4);
            logBuilder.append("[大表优化] 源表约 ").append(srcEst).append(" 行(>千万): DataX 批次=").append(dxBatch).append(", 并发=").append(dxChannel).append("\n");
        }
        String jobFile = dataxService.generateJobJson(
                ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(),
                task.getSourceDb(), task.getSourceTable(), task.getTargetTable(), columns, bizdate, dxBatch, dxChannel);
        logBuilder.append("[DataX] 配置生成完成: ").append(jobFile).append("\n");

        // ========== 第三步：执行 DataX ==========

        logBuilder.append("[DataX] 开始同步数据...\n");
        ProcessUtil.ExecResult result;
        try {
            result = dataxService.runJob(jobFile);
            logBuilder.append(result.getOutput());
        } finally {
            // 含密码临时文件: 无论 runJob 成功或抛异常都删除(P1: 异常路径防凭据残留)
            try {
                new java.io.File(jobFile).delete();
            } catch (Exception e) {
                log.warn("删除 DataX 临时配置文件失败: {} - {}", jobFile, e.getMessage());
            }
        }

        if (result.getExitCode() != 0) {
            throw new RuntimeException("DataX 执行失败，退出码: " + result.getExitCode());
        }

        // ========== 第四步：后处理（失败不影响整体结果） ==========

        // 4.1 更新统计
        logBuilder.append("\n[后处理] ANALYZE TABLE...");
        try {
            hiveService.executeDDL("ANALYZE TABLE " + targetTable);
            logBuilder.append(" 成功\n");
        } catch (Exception e) {
            logBuilder.append(" 跳过(").append(e.getMessage()).append(")\n");
        }

        logBuilder.append("\n[完成] 耗时: ").append(result.getDurationMs() / 1000).append("秒\n");
    }

    /**
     * 手动触发运行一个 ODS 同步任务(DnSyncTask), 复用与调度一致的 {@link #executeSyncTask} 核心
     * (预检→幂等建表→DataX 同步→ANALYZE), 并记录一条 manual 执行记录。供 AI 工具 run_ods_task / 手动触发复用。
     * 同步执行(阻塞至完成); 日志脱敏后入库。返回执行记录(含 status/log)。
     */
    public com.datanote.domain.orchestration.model.DnTaskExecution runSyncTaskManually(Long taskId, String triggerUser) {
        DnSyncTask task = syncTaskMapper.selectById(taskId);
        if (task == null) throw new BusinessException("ODS 同步任务不存在: " + taskId);
        com.datanote.domain.orchestration.model.DnTaskExecution exec = new com.datanote.domain.orchestration.model.DnTaskExecution();
        exec.setSyncTaskId(taskId);
        exec.setTaskType(Constants.TASK_TYPE_SYNC_TASK);
        exec.setTriggerType("manual");
        exec.setStatus("RUNNING");
        exec.setStartTime(LocalDateTime.now());
        taskExecutionMapper.insert(exec);
        long startMs = System.currentTimeMillis();
        StringBuilder logBuilder = new StringBuilder();
        try {
            String bizdate = java.time.LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            executeSyncTask(taskId, bizdate, logBuilder);   // 复用调度同款核心(零重复)
            exec.setStatus("SUCCESS");
        } catch (Exception e) {
            logBuilder.append("\n[ERROR] ").append(SecretRedactor.redact(e.getMessage() == null ? "" : e.getMessage()));
            exec.setStatus("FAILED");
        } finally {
            exec.setEndTime(LocalDateTime.now());
            exec.setDuration((int) ((System.currentTimeMillis() - startMs) / 1000));
            String lg = SecretRedactor.redact(logBuilder.toString());   // 日志脱敏(防 DataX 输出残留凭据)
            exec.setLog(lg.length() > 50000 ? lg.substring(lg.length() - 50000) : lg);
            taskExecutionMapper.updateById(exec);
        }
        return exec;
    }

    /**
     * 手动触发运行一个开发脚本(DnScript, 如 DWD/DWS/ADS 加工 SQL), 复用与调度一致的 {@link #executeScript} 核心,
     * 记录 manual 执行记录, 日志脱敏。供 AI 工具 run_script / 手动触发复用。同步执行(阻塞至完成)。
     */
    public com.datanote.domain.orchestration.model.DnTaskExecution runScriptManually(Long scriptId, String triggerUser) {
        DnScript script = scriptMapper.selectById(scriptId);
        if (script == null) throw new BusinessException("脚本不存在: " + scriptId);
        com.datanote.domain.orchestration.model.DnTaskExecution exec = new com.datanote.domain.orchestration.model.DnTaskExecution();
        exec.setScriptId(scriptId);
        exec.setTaskType(Constants.TASK_TYPE_SCRIPT);
        exec.setTriggerType("manual");
        exec.setStatus("RUNNING");
        exec.setStartTime(LocalDateTime.now());
        if (triggerUser != null) exec.setExecutor(triggerUser);
        taskExecutionMapper.insert(exec);
        long startMs = System.currentTimeMillis();
        StringBuilder logBuilder = new StringBuilder();
        try {
            String bizdate = java.time.LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            executeScript(scriptId, bizdate, logBuilder);   // 复用调度同款核心(零重复)
            exec.setStatus("SUCCESS");
        } catch (Exception e) {
            logBuilder.append("\n[ERROR] ").append(SecretRedactor.redact(e.getMessage() == null ? "" : e.getMessage()));
            exec.setStatus("FAILED");
        } finally {
            exec.setEndTime(LocalDateTime.now());
            exec.setDuration((int) ((System.currentTimeMillis() - startMs) / 1000));
            String lg = SecretRedactor.redact(logBuilder.toString());
            exec.setLog(lg.length() > 50000 ? lg.substring(lg.length() - 50000) : lg);
            taskExecutionMapper.updateById(exec);
        }
        return exec;
    }

    private DnDatasource resolveDatasource(DnSyncTask task) {
        if (task.getSourceDsId() == null) {
            throw new RuntimeException("同步任务未配置源数据源");
        }
        DnDatasource ds = datasourceMapper.selectById(task.getSourceDsId());
        if (ds == null) {
            throw new RuntimeException("数据源不存在: " + task.getSourceDsId());
        }
        ds.setPassword(CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey));
        return ds;
    }

    private List<ColumnInfo> getSourceColumns(DnDatasource ds, DnSyncTask task) throws Exception {
        return metadataService.getColumnsByConnection(
                ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(),
                task.getSourceDb(), task.getSourceTable());
    }

    private void executeScript(Long scriptId, String bizdate, StringBuilder logBuilder) throws Exception {
        DnScript script = scriptMapper.selectById(scriptId);
        if (script == null) throw new RuntimeException("脚本不存在: " + scriptId);

        logBuilder.append("[").append(nowTime()).append("] 开始执行脚本: ").append(script.getScriptName()).append("\n");
        logBuilder.append("脚本类型: ").append(script.getScriptType()).append("\n");
        logBuilder.append("数据日期: ").append(bizdate).append("\n\n");

        String content = script.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("脚本内容为空");
        }

        String executeSql = content.replace("${bizdate}", bizdate);
        logBuilder.append("[参数替换] ${bizdate} -> ").append(bizdate).append("\n\n");

        String scriptType = script.getScriptType() != null ? script.getScriptType().toLowerCase() : "hive";

        if ("shell".equals(scriptType)) {
            if (!shellEnabled) {
                throw new BusinessException("Shell 脚本执行已禁用");
            }
            String[] cmd = {"/bin/bash", "-c", executeSql};
            int timeout = script.getTimeoutSeconds() != null ? script.getTimeoutSeconds() : 3600;
            // P1-03 强约束: 清子进程敏感环境变量, 防 Shell 任务读取系统凭据(密码/密钥/token)
            ProcessUtil.ExecResult result = ProcessUtil.exec(cmd, timeout, true);
            logBuilder.append(result.getOutput());
            if (result.getExitCode() != 0) {
                throw new RuntimeException("Shell 执行失败，退出码: " + result.getExitCode());
            }
        } else {
            // 大数据调度优化: 并行度会话变量(守卫式, 不支持自动忽略) + 脚本配置的查询超时(防亿级查询失控)
            java.util.List<String> sessionSets = new java.util.ArrayList<>();
            if (dorisParallel > 0) sessionSets.add("SET parallel_fragment_exec_instance_num = " + dorisParallel);
            // 深度调优: 注入按集群配置的额外会话变量(exec_mem_limit 等), 守卫式
            if (dorisSessionVars != null && !dorisSessionVars.trim().isEmpty()) {
                for (String kv : dorisSessionVars.split(",")) {
                    String t = kv.trim();
                    if (!t.isEmpty()) sessionSets.add(t.toUpperCase().startsWith("SET ") ? t : ("SET " + t));
                }
            }
            int sqlTimeout = script.getTimeoutSeconds() != null && script.getTimeoutSeconds() > 0 ? script.getTimeoutSeconds() : 0;
            if (!sessionSets.isEmpty() || sqlTimeout > 0) {
                logBuilder.append("[大数据优化] 并行度=").append(dorisParallel > 0 ? dorisParallel : "默认")
                          .append(sqlTimeout > 0 ? ("，查询超时=" + sqlTimeout + "s") : "").append("\n");
            }
            String[] statements = splitSQL(executeSql);
            for (int i = 0; i < statements.length; i++) {
                String stmt = statements[i].trim();
                if (stmt.isEmpty()) continue;

                logBuilder.append("[执行第 ").append(i + 1).append("/").append(statements.length).append(" 条语句]\n");
                Map<String, Object> result = hiveService.executeSQL(stmt, false, sqlTimeout, sessionSets);
                Boolean success = (Boolean) result.get("success");
                if (success == null || !success) {
                    throw new RuntimeException("DorisSQL 执行失败: " + result.get("error"));
                }

                @SuppressWarnings("unchecked")
                List<String> hiveLogs = (List<String>) result.get("hiveLogs");
                if (hiveLogs != null) {
                    for (String hiveLog : hiveLogs) {
                        logBuilder.append(hiveLog).append("\n");
                    }
                }
                logBuilder.append("[语句 ").append(i + 1).append(" 完成] 耗时 ")
                          .append(result.get("duration")).append("ms\n\n");
            }
        }
        logBuilder.append("[全部完成]\n");
    }

    // ======================== 手动重试 ========================

    /**
     * 手动重试失败或暂停的任务
     *
     * @param runId 运行记录 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void retryTask(Long runId) {
        if (runId == null) throw new BusinessException("重试失败：运行记录 ID 不能为空");
        DnSchedulerRun run = runMapper.selectById(runId);
        if (run == null) throw new BusinessException("运行记录不存在");
        Integer status = run.getStatus();
        if (status == null ||
            (status != DnSchedulerRun.STATUS_FAILED && status != DnSchedulerRun.STATUS_PAUSED)) {
            throw new BusinessException("只能重试失败或暂停的任务，当前状态: " + status);
        }

        run.setStatus(DnSchedulerRun.STATUS_WAITING);
        run.setRetryCount(0); // 手动重试重置计数
        run.setLog(null);
        run.setStartTime(null);
        run.setEndTime(null);
        runMapper.updateById(run);

        taskSchedulerService.setEnabled(true);
        taskSchedulerService.processWaitingTasks(run.getRunDate(), run.getRunType());
    }

    // ======================== 工具方法 ========================

    /**
     * 指数退避 + Full Jitter
     */
    private long calculateRetryDelay(int attempt) {
        // 限制移位次数，避免 attempt 过大导致 1L<<attempt 溢出为负值（进而使随机区间非法）
        int shift = (attempt < 0) ? 0 : Math.min(attempt, 30);
        long exponential = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * (1L << shift));
        // exponential 恒 >= RETRY_BASE_MS，确保随机区间 origin < bound
        return ThreadLocalRandom.current().nextLong(RETRY_BASE_MS, exponential + 1);
    }

    private int getTaskTimeout(DnSchedulerRun run) {
        if (Constants.TASK_TYPE_SCRIPT.equals(run.getTaskType())) {
            DnScript s = scriptMapper.selectById(run.getTaskId());
            return (s != null && s.getTimeoutSeconds() != null && s.getTimeoutSeconds() > 0)
                    ? s.getTimeoutSeconds() : 7200; // 默认 2 小时
        } else {
            DnSyncTask t = syncTaskMapper.selectById(run.getTaskId());
            return (t != null && t.getTimeoutSeconds() != null && t.getTimeoutSeconds() > 0)
                    ? t.getTimeoutSeconds() : 3600; // 默认 1 小时
        }
    }

    private int getTaskMaxRetries(DnSchedulerRun run) {
        if (Constants.TASK_TYPE_SCRIPT.equals(run.getTaskType())) {
            DnScript s = scriptMapper.selectById(run.getTaskId());
            return (s != null && s.getRetryTimes() != null) ? s.getRetryTimes() : 0;
        } else {
            DnSyncTask t = syncTaskMapper.selectById(run.getTaskId());
            return (t != null && t.getRetryTimes() != null) ? t.getRetryTimes() : 0;
        }
    }

    private String truncateLog(StringBuilder logBuilder) {
        if (logBuilder.length() > MAX_LOG_SIZE) {
            logBuilder.setLength(MAX_LOG_SIZE);
            logBuilder.append("\n\n[日志已截断，超过 1MB 限制]");
        }
        return SecretRedactor.redact(logBuilder.toString());
    }

    private String nowTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private String[] splitSQL(String sql) {
        return java.util.Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("--"))
                .toArray(String[]::new);
    }

    // ======================== 结构化执行指标落盘 ========================

    private void saveTaskExecution(DnSchedulerRun run, String status, String logText) {
        try {
            DnTaskExecution exec = new DnTaskExecution();
            if (Constants.TASK_TYPE_SCRIPT.equals(run.getTaskType())) {
                exec.setScriptId(run.getTaskId());
            } else {
                exec.setSyncTaskId(run.getTaskId());
            }
            exec.setTaskType(run.getTaskType());
            exec.setTriggerType(run.getRunType());
            exec.setStatus(status);
            exec.setStartTime(run.getStartTime());
            exec.setEndTime(run.getEndTime());
            exec.setExecutor("local");
            exec.setCreatedAt(LocalDateTime.now());

            // 计算耗时
            if (run.getStartTime() != null && run.getEndTime() != null) {
                exec.setDuration((int) java.time.Duration.between(run.getStartTime(), run.getEndTime()).getSeconds());
            }

            // 从 DataX 日志中解析读写行数
            if (Constants.TASK_TYPE_SYNC_TASK.equals(run.getTaskType()) && logText != null) {
                exec.setReadCount(parseCountFromLog(logText, "读出记录总数"));
                exec.setWriteCount(parseCountFromLog(logText, "写出记录总数"));
                long readErr = parseCountFromLog(logText, "读写失败总数");
                if (readErr <= 0) readErr = parseCountFromLog(logText, "脏数据");
                exec.setErrorCount(readErr);
            }

            taskExecutionMapper.insert(exec);
        } catch (Exception e) {
            log.warn("保存执行指标失败: {}", e.getMessage());
        }
    }

    /**
     * 从 DataX 日志中解析数值（如 "读出记录总数 : 12345"）
     */
    private long parseCountFromLog(String logText, String keyword) {
        if (logText == null || keyword == null) return 0;
        int idx = logText.indexOf(keyword);
        if (idx < 0) return 0;
        String after = logText.substring(idx + keyword.length());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s*[:：]?\\s*(\\d+)").matcher(after);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
