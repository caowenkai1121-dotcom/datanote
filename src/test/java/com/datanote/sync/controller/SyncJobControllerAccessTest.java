package com.datanote.sync.controller;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.integration.controller.SyncJobController;
import com.datanote.domain.integration.mapper.DnSyncFolderMapper;
import com.datanote.domain.integration.mapper.DnSyncJobMapper;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.integration.service.SyncJobExecutor;
import com.datanote.domain.integration.service.SyncJobService;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncJobControllerAccessTest {

    @Test
    void online_deniedByJobAccess_doesNotUpdateJob() {
        SyncJobService service = mock(SyncJobService.class);
        DnSyncJobMapper jobMapper = mock(DnSyncJobMapper.class);
        doThrow(new BusinessException("无权访问该同步任务")).when(service).requireJobAccess(7L);
        SyncJobController controller = controller(service, jobMapper, mock(DnTaskExecutionMapper.class));

        assertThrows(BusinessException.class, () -> controller.online(7L));

        verify(jobMapper, never()).updateById(any());
    }

    @Test
    void executions_deniedByJobAccess_doesNotQueryExecutions() {
        SyncJobService service = mock(SyncJobService.class);
        DnTaskExecutionMapper executionMapper = mock(DnTaskExecutionMapper.class);
        doThrow(new BusinessException("无权访问该同步任务")).when(service).requireJobAccess(7L);
        SyncJobController controller = controller(service, mock(DnSyncJobMapper.class), executionMapper);

        assertThrows(BusinessException.class, () -> controller.executions(7L));

        verify(executionMapper, never()).selectList(any());
    }

    private static SyncJobController controller(SyncJobService service,
                                               DnSyncJobMapper jobMapper,
                                               DnTaskExecutionMapper executionMapper) {
        return new SyncJobController(
                service, mock(SyncJobExecutor.class),
                executionMapper, jobMapper, mock(DnSyncFolderMapper.class),
                mock(com.datanote.domain.integration.mapper.DnSyncJobAuditMapper.class),
                mock(com.datanote.domain.integration.service.DataReconciliationService.class),
                mock(com.datanote.domain.integration.service.CdcEngineManager.class),
                mock(com.datanote.domain.integration.mapper.DnSyncErrorRowMapper.class),
                mock(com.datanote.domain.integration.mapper.DnCdcDeadLetterMapper.class),
                mock(com.datanote.domain.integration.schema.TableSchemaService.class),
                mock(com.datanote.domain.integration.schema.SchemaDriftService.class),
                mock(com.datanote.platform.iam.DataAclService.class));
    }
}
