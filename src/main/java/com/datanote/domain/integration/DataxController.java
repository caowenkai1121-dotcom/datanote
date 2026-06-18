package com.datanote.domain.integration;

import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.common.model.R;
import com.datanote.domain.integration.dto.DataxCreateAndSyncRequest;
import com.datanote.domain.integration.dto.DataxGenerateJobRequest;
import com.datanote.domain.integration.dto.DataxRunRequest;
import com.datanote.domain.integration.DataxService;
import com.datanote.domain.integration.HiveService;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.common.util.CryptoUtil;
import com.datanote.domain.orchestration.util.ProcessUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DataX 同步管理 Controller
 */
@Slf4j
@RestController
@Tag(name = "DataX 数据同步", description = "MySQL 到 Doris 的数据同步管理")
@RequestMapping("/api/datax")
@RequiredArgsConstructor
public class DataxController {

    private final DataxService dataxService;
    private final MetadataService metadataService;
    private final HiveService hiveService;
    private final DnDatasourceMapper datasourceMapper;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final DnSyncTaskMapper syncTaskMapper;

    @Value("${spring.datasource.url:}")
    private String defaultDbUrl;

    @Value("${spring.datasource.username:root}")
    private String defaultDbUser;

    @Value("${spring.datasource.password:}")
    private String defaultDbPass;

    @Value("${datanote.crypto.key:}")
    private String cryptoKey;

    /**
     * 生成 DataX JSON 配置
     */
    @Operation(summary = "生成 DataX 任务配置")
    @PostMapping("/generate-job")
    public R<Map<String, String>> generateJob(@RequestBody DataxGenerateJobRequest body) {
        try {
            String db = body.getDb();
            String table = body.getTable();
            String syncMode = body.getSyncMode() != null ? body.getSyncMode() : "df";
            DnDatasource ds = resolveDatasource(body.getDatasourceId(), null);

            List<ColumnInfo> columns = resolveColumns(body.getDatasourceId(), null, ds, db, table);
            String odsTable = hiveService.getOdsTableName(db, table, syncMode);
            String jobPath = dataxService.generateJobJson(
                    ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(),
                    db, table, odsTable, columns);
            String jobId = dataxService.registerJob(jobPath);

            Map<String, String> result = new HashMap<>();
            result.put("jobId", jobId);
            result.put("odsTable", odsTable);
            return R.ok(result);
        } catch (Exception e) {
            log.error("生成 DataX 任务配置失败", e);
            return R.fail("生成任务配置失败");
        }
    }

    /**
     * 执行 DataX 同步任务
     */
    @Operation(summary = "执行 DataX 同步任务")
    @PostMapping("/run")
    public R<Map<String, Object>> run(@RequestBody DataxRunRequest body) {
        String jobPath = null;
        try {
            String jobId = body == null ? null : body.getJobId();
            if (jobId == null || jobId.trim().isEmpty()) {
                return R.fail("DataX jobId 不能为空");
            }
            jobPath = dataxService.consumeJob(jobId);
            ProcessUtil.ExecResult execResult = dataxService.runJob(jobPath);

            Map<String, Object> data = new HashMap<>();
            data.put("exitCode", execResult.getExitCode());
            data.put("durationMs", execResult.getDurationMs());
            data.put("output", safeOutput(execResult.getOutput(), defaultDbPass));
            data.put("success", execResult.getExitCode() == 0);
            return R.ok(data);
        } catch (Exception e) {
            log.error("执行 DataX 同步任务失败", e);
            return R.fail("执行同步任务失败");
        } finally {
            if (jobPath != null) {
                try { new java.io.File(jobPath).delete(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 一键建表并同步
     */
    @Operation(summary = "一键建表并同步数据")
    @PostMapping("/create-and-sync")
    public R<Map<String, Object>> createAndSync(@RequestBody DataxCreateAndSyncRequest body) {
        long startMs = System.currentTimeMillis();
        // 创建执行记录
        DnTaskExecution exec = new DnTaskExecution();
        exec.setSyncTaskId(body.getSyncTaskId());
        exec.setTaskType("syncTask");
        exec.setTriggerType("manual");
        exec.setStatus("RUNNING");
        exec.setStartTime(java.time.LocalDateTime.now());
        if (body.getSyncTaskId() != null) {
            taskExecutionMapper.insert(exec);
        }

        try {
            String db = body.getDb();
            String table = body.getTable();
            String syncMode = body.getSyncMode() != null ? body.getSyncMode() : "df";
            DnDatasource ds = resolveDatasource(body.getDatasourceId(), body.getSyncTaskId());

            List<ColumnInfo> columns = resolveColumns(body.getDatasourceId(), body.getSyncTaskId(), ds, db, table);
            String odsTable = hiveService.getOdsTableName(db, table, syncMode);

            String ddl = hiveService.generateDDL(db, table, columns, syncMode);
            hiveService.executeDDL(ddl);

            String today = java.time.LocalDate.now().minusDays(1).toString();

            String jobPath = dataxService.generateJobJson(
                    ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(),
                    db, table, odsTable, columns, today);

            ProcessUtil.ExecResult execResult = dataxService.runJob(jobPath);
            String safeExecOutput = safeOutput(execResult.getOutput(), ds.getPassword(), defaultDbPass);

            // 执行完成后清理含密码的 JSON 配置文件
            try { new java.io.File(jobPath).delete(); } catch (Exception ignored) {}

            // 更新执行记录
            if (exec.getId() != null) {
                exec.setStatus(execResult.getExitCode() == 0 ? "SUCCESS" : "FAILED");
                exec.setEndTime(java.time.LocalDateTime.now());
                exec.setDuration((int)((System.currentTimeMillis() - startMs) / 1000));
                exec.setLog(safeExecOutput != null && safeExecOutput.length() > 50000
                        ? safeExecOutput.substring(safeExecOutput.length() - 50000)
                        : safeExecOutput);
                taskExecutionMapper.updateById(exec);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("odsTable", odsTable);
            data.put("ddl", ddl);
            data.put("exitCode", execResult.getExitCode());
            data.put("durationMs", execResult.getDurationMs());
            data.put("success", execResult.getExitCode() == 0);
            data.put("output", safeExecOutput);
            return R.ok(data);
        } catch (Exception e) {
            log.error("一键建表并同步失败", e);
            // 更新执行记录为失败
            if (exec.getId() != null) {
                exec.setStatus("FAILED");
                exec.setEndTime(java.time.LocalDateTime.now());
                exec.setDuration((int)((System.currentTimeMillis() - startMs) / 1000));
                exec.setLog(safeOutput(e.getMessage(), defaultDbPass));
                taskExecutionMapper.updateById(exec);
            }
            return R.fail("建表同步操作失败");
        }
    }

    /** 单参重载(仅按数据源ID解析); 供单测与内部按ID直解场景复用。 */
    private DnDatasource resolveDatasource(String dsIdStr) {
        return resolveDatasource(dsIdStr, null);
    }

    private DnDatasource resolveDatasource(String dsIdStr, Long syncTaskId) {
        if ((dsIdStr == null || dsIdStr.isEmpty()) && syncTaskId != null) {
            DnSyncTask task = syncTaskMapper.selectById(syncTaskId);
            if (task != null && task.getSourceDsId() != null) {
                dsIdStr = String.valueOf(task.getSourceDsId());
            }
        }
        if (dsIdStr != null && !dsIdStr.isEmpty()) {
            try {
                DnDatasource ds = datasourceMapper.selectById(Long.valueOf(dsIdStr));
                if (ds != null) {
                    ds.setPassword(CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey));
                    return ds;
                }
            } catch (NumberFormatException ignored) {}
        }
        return getDefaultDatasource();
    }

    private List<ColumnInfo> resolveColumns(String dsIdStr, Long syncTaskId, DnDatasource ds, String db, String table) throws Exception {
        if ((dsIdStr != null && !dsIdStr.isEmpty()) || syncTaskId != null) {
            return metadataService.getColumnsByConnection(
                    ds.getHost(), ds.getPort(), ds.getUsername(), ds.getPassword(), db, table);
        }
        return metadataService.getColumns(db, table);
    }

    private DnDatasource getDefaultDatasource() {
        DnDatasource ds = new DnDatasource();
        try {
            String hostPort = defaultDbUrl.split("//")[1].split("/")[0];
            ds.setHost(hostPort.split(":")[0]);
            ds.setPort(Integer.parseInt(hostPort.split(":")[1]));
        } catch (Exception e) {
            ds.setHost("127.0.0.1");
            ds.setPort(3306);
        }
        ds.setUsername(defaultDbUser);
        ds.setPassword(defaultDbPass);
        return ds;
    }

    private String safeOutput(String output, String... secrets) {
        if (output == null) {
            return null;
        }
        String safe = output;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.trim().isEmpty()) {
                    safe = safe.replace(secret, "******");
                }
            }
        }
        safe = safe.replaceAll("(?i)(\"password\"\\s*:\\s*\")[^\"]*(\")", "$1******$2");
        safe = safe.replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s,;]+", "$1******");
        safe = safe.replaceAll("(?i)(pwd\\s*[:=]\\s*)[^\\s,;]+", "$1******");
        return safe;
    }
}
