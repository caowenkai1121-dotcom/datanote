package com.datanote.domain.integration.controller;

import com.datanote.domain.integration.mapper.DnSyncFolderMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.common.model.R;
import com.datanote.domain.integration.service.SyncJobExecutor;
import com.datanote.domain.integration.service.SyncJobService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SyncJobController 单测：停止(B7)、预检(B24)、保存校验失败(B8) 的返回码与数据。
 */
class SyncJobControllerTest {

    private final SyncJobService service = mock(SyncJobService.class);
    private final SyncJobExecutor executor = mock(SyncJobExecutor.class);
    private final SyncJobController controller = new SyncJobController(
            service, executor,
            mock(DnTaskExecutionMapper.class), mock(DnSyncJobMapper.class), mock(DnSyncFolderMapper.class),
            mock(com.datanote.domain.integration.mapper.DnSyncJobAuditMapper.class),
            mock(com.datanote.domain.integration.service.DataReconciliationService.class),
            mock(com.datanote.domain.integration.service.CdcEngineManager.class),
            mock(com.datanote.domain.integration.mapper.DnSyncErrorRowMapper.class),
            mock(com.datanote.domain.integration.mapper.DnCdcDeadLetterMapper.class),
            mock(com.datanote.domain.integration.schema.TableSchemaService.class),
            mock(com.datanote.domain.integration.schema.SchemaDriftService.class),
            mock(com.datanote.platform.iam.DataAclService.class));

    @Test
    void stop_hitRunningTask_returnsOk() {
        when(executor.stop(5L)).thenReturn(true);
        R<String> r = controller.stop(5L);
        assertEquals(R.CODE_SUCCESS, r.getCode());
    }

    @Test
    void stop_noRunningTask_returnsFail() {
        when(executor.stop(9L)).thenReturn(false);
        R<String> r = controller.stop(9L);
        assertEquals(R.CODE_FAIL, r.getCode());
    }

    @Test
    void save_validationError_returnsFail() {
        DnSyncJob job = new DnSyncJob();
        when(service.save(any(DnSyncJob.class))).thenThrow(new IllegalArgumentException("非法同步模式"));
        R<DnSyncJob> r = controller.save(job);
        assertEquals(R.CODE_FAIL, r.getCode());
        assertEquals("非法同步模式", r.getMsg());
    }

    @Test
    void precheck_returnsServiceResult() {
        DnSyncJob job = new DnSyncJob();
        Map<String, Object> result = Collections.singletonMap("ok", Boolean.TRUE);
        when(service.precheck(eq(job))).thenReturn(result);
        R<Map<String, Object>> r = controller.precheck(job);
        assertEquals(R.CODE_SUCCESS, r.getCode());
        assertEquals(Boolean.TRUE, r.getData().get("ok"));
    }
}
