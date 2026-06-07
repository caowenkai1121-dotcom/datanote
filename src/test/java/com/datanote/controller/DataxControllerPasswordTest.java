package com.datanote.controller;

import com.datanote.domain.integration.DataxController;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.metadata.model.ColumnInfo;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.model.DnSyncTask;
import com.datanote.common.model.R;
import com.datanote.domain.integration.dto.DataxCreateAndSyncRequest;
import com.datanote.domain.integration.dto.DataxGenerateJobRequest;
import com.datanote.domain.integration.DataxService;
import com.datanote.domain.integration.HiveService;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.common.util.CryptoUtil;
import com.datanote.domain.orchestration.util.ProcessUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataxControllerPasswordTest {

    @Test
    void resolveDatasourceDecryptsStoredPasswordBeforeGeneratingDataxJob() {
        DnDatasourceMapper mapper = mock(DnDatasourceMapper.class);
        DnDatasource datasource = new DnDatasource();
        datasource.setId(2L);
        datasource.setHost("1.95.167.10");
        datasource.setPort(3306);
        datasource.setUsername("root");
        datasource.setPassword(CryptoUtil.encrypt("source-secret", "DataNote_AES_Key"));
        when(mapper.selectById(2L)).thenReturn(datasource);

        DataxController controller = new DataxController(null, null, null, mapper, null, null);
        ReflectionTestUtils.setField(controller, "cryptoKey", "DataNote_AES_Key");

        DnDatasource resolved = ReflectionTestUtils.invokeMethod(controller, "resolveDatasource", "2");

        assertEquals("source-secret", resolved.getPassword());
    }

    @Test
    void generateJobUsesSelectedDatasourceConnectionForColumnMetadata() throws Exception {
        DnDatasourceMapper mapper = mock(DnDatasourceMapper.class);
        MetadataService metadataService = mock(MetadataService.class);
        HiveService hiveService = mock(HiveService.class);
        DataxService dataxService = mock(DataxService.class);

        DnDatasource datasource = new DnDatasource();
        datasource.setId(2L);
        datasource.setHost("1.95.167.10");
        datasource.setPort(3306);
        datasource.setUsername("root");
        datasource.setPassword(CryptoUtil.encrypt("source-secret", "DataNote_AES_Key"));
        when(mapper.selectById(2L)).thenReturn(datasource);

        ColumnInfo id = new ColumnInfo();
        id.setName("id");
        when(metadataService.getColumnsByConnection(
                "1.95.167.10", 3306, "root", "source-secret", "xh_dms", "t_after_sales_order_header"))
                .thenReturn(Arrays.asList(id));
        when(hiveService.getOdsTableName("xh_dms", "t_after_sales_order_header", "df"))
                .thenReturn("ods_xh_dms_t_after_sales_order_header_df");
        when(dataxService.generateJobJson(
                eq("1.95.167.10"), eq(3306), eq("root"), eq("source-secret"),
                eq("xh_dms"), eq("t_after_sales_order_header"),
                eq("ods_xh_dms_t_after_sales_order_header_df"), anyList()))
                .thenReturn("/tmp/job.json");

        DataxController controller = new DataxController(
                dataxService, metadataService, hiveService, mapper, mock(DnTaskExecutionMapper.class), null);
        ReflectionTestUtils.setField(controller, "cryptoKey", "DataNote_AES_Key");

        DataxGenerateJobRequest request = new DataxGenerateJobRequest();
        request.setDatasourceId("2");
        request.setDb("xh_dms");
        request.setTable("t_after_sales_order_header");
        request.setSyncMode("df");

        R<Map<String, String>> response = controller.generateJob(request);

        assertEquals(0, response.getCode());
        verify(metadataService).getColumnsByConnection(
                "1.95.167.10", 3306, "root", "source-secret", "xh_dms", "t_after_sales_order_header");
        verify(metadataService, never()).getColumns(anyString(), anyString());
    }

    @Test
    void createAndSyncResolvesDatasourceFromSyncTaskWhenRequestOmitsDatasourceId() throws Exception {
        DnDatasourceMapper datasourceMapper = mock(DnDatasourceMapper.class);
        DnSyncTaskMapper syncTaskMapper = mock(DnSyncTaskMapper.class);
        MetadataService metadataService = mock(MetadataService.class);
        HiveService hiveService = mock(HiveService.class);
        DataxService dataxService = mock(DataxService.class);

        DnSyncTask task = new DnSyncTask();
        task.setId(7L);
        task.setSourceDsId(2L);
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
        when(hiveService.getOdsTableName("xh_dms", "t_after_sales_order_header", "df"))
                .thenReturn("ods_xh_dms_t_after_sales_order_header_df");
        when(hiveService.generateDDL(eq("xh_dms"), eq("t_after_sales_order_header"), anyList(), eq("df")))
                .thenReturn("CREATE TABLE ...");
        when(dataxService.generateJobJson(
                eq("1.95.167.10"), eq(3306), eq("root"), eq("source-secret"),
                eq("xh_dms"), eq("t_after_sales_order_header"),
                eq("ods_xh_dms_t_after_sales_order_header_df"), anyList(), anyString()))
                .thenReturn("/tmp/job.json");
        ProcessUtil.ExecResult result = new ProcessUtil.ExecResult();
        result.setExitCode(0);
        result.setOutput("");
        when(dataxService.runJob("/tmp/job.json")).thenReturn(result);

        DataxController controller = new DataxController(
                dataxService, metadataService, hiveService,
                datasourceMapper, mock(DnTaskExecutionMapper.class), syncTaskMapper);
        ReflectionTestUtils.setField(controller, "cryptoKey", "DataNote_AES_Key");

        DataxCreateAndSyncRequest request = new DataxCreateAndSyncRequest();
        request.setSyncTaskId(7L);
        request.setDb("xh_dms");
        request.setTable("t_after_sales_order_header");
        request.setSyncMode("df");

        R<Map<String, Object>> response = controller.createAndSync(request);

        assertEquals(0, response.getCode());
        verify(metadataService).getColumnsByConnection(
                "1.95.167.10", 3306, "root", "source-secret", "xh_dms", "t_after_sales_order_header");
        verify(metadataService, never()).getColumns(anyString(), anyString());
    }
}
