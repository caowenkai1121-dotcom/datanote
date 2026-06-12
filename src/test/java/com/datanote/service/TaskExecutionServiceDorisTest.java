package com.datanote.service;

import com.datanote.common.LogBroadcastService;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.domain.integration.DataxService;
import com.datanote.domain.integration.HiveService;
import com.datanote.domain.orchestration.TaskDependencyService;
import com.datanote.domain.orchestration.TaskExecutionService;
import com.datanote.domain.orchestration.TaskSchedulerService;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.orchestration.mapper.DnSchedulerRunMapper;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.common.util.CryptoUtil;
import com.datanote.domain.orchestration.util.ProcessUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskExecutionServiceDorisTest {

    @Test
    void scheduledSyncUsesSelectedDatasourceForColumnsAndDataxPassword() throws Exception {
        DnScriptMapper scriptMapper = mock(DnScriptMapper.class);
        DnSyncTaskMapper syncTaskMapper = mock(DnSyncTaskMapper.class);
        DnSchedulerRunMapper runMapper = mock(DnSchedulerRunMapper.class);
        DnDatasourceMapper datasourceMapper = mock(DnDatasourceMapper.class);
        DnTaskExecutionMapper taskExecutionMapper = mock(DnTaskExecutionMapper.class);
        HiveService hiveService = mock(HiveService.class);
        DataxService dataxService = mock(DataxService.class);
        MetadataService metadataService = mock(MetadataService.class);

        DnSyncTask task = new DnSyncTask();
        task.setId(7L);
        task.setTaskName("after_sales_header");
        task.setSourceDsId(2L);
        task.setSourceDb("xh_dms");
        task.setSourceTable("t_after_sales_order_header");
        task.setTargetTable("ods_xh_dms_t_after_sales_order_header_df");
        task.setSyncMode("df");
        when(syncTaskMapper.selectById(7L)).thenReturn(task);

        DnDatasource datasource = new DnDatasource();
        datasource.setId(2L);
        datasource.setHost("1.95.167.10");
        datasource.setPort(3306);
        datasource.setUsername("root");
        datasource.setPassword(CryptoUtil.encrypt("source-secret", "DataNote_AES_Key"));
        when(datasourceMapper.selectById(2L)).thenReturn(datasource);

        ColumnInfo id = new ColumnInfo();
        id.setName("id");
        when(metadataService.getColumnsByConnection(
                "1.95.167.10", 3306, "root", "source-secret", "xh_dms", "t_after_sales_order_header"))
                .thenReturn(Arrays.asList(id));
        when(hiveService.generateDDL(eq("xh_dms"), eq("t_after_sales_order_header"), anyList(), eq("df")))
                .thenReturn("CREATE TABLE ...");
        when(dataxService.generateJobJson(
                eq("1.95.167.10"), eq(3306), eq("root"), eq("source-secret"),
                eq("xh_dms"), eq("t_after_sales_order_header"),
                eq("ods_xh_dms_t_after_sales_order_header_df"), anyList(), eq("2026-05-25")))
                .thenReturn("/tmp/job.json");
        ProcessUtil.ExecResult result = new ProcessUtil.ExecResult();
        result.setExitCode(0);
        result.setOutput("读出记录总数                    :                 276\n读写失败总数                    :                   0\n");
        result.setDurationMs(1000);
        when(dataxService.runJob("/tmp/job.json")).thenReturn(result);

        TaskExecutionService service = new TaskExecutionService(
                scriptMapper,
                syncTaskMapper,
                runMapper,
                datasourceMapper,
                taskExecutionMapper,
                hiveService,
                dataxService,
                metadataService,
                mock(TaskDependencyService.class),
                mock(LogBroadcastService.class),
                mock(TaskSchedulerService.class),
                mock(com.datanote.platform.notify.NotificationService.class));
        ReflectionTestUtils.setField(service, "cryptoKey", "DataNote_AES_Key");

        ReflectionTestUtils.invokeMethod(service, "executeSyncTask", 7L, "2026-05-25", new StringBuilder());

        verify(metadataService).getColumnsByConnection(
                "1.95.167.10", 3306, "root", "source-secret", "xh_dms", "t_after_sales_order_header");
        verify(metadataService, never()).getColumns("xh_dms", "t_after_sales_order_header");
    }
}
