package com.datanote.sync.service;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.integration.mapper.*;
import com.datanote.domain.integration.model.DnSyncJob;
import com.datanote.domain.integration.service.*;
import com.datanote.platform.iam.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncJobServiceAccessTest {

    @Mock private DnSyncJobMapper syncJobMapper;
    @Mock private com.datanote.domain.datasource.mapper.DnDatasourceMapper datasourceMapper;
    @Mock private com.datanote.domain.integration.connector.ConnectionManager connectionManager;
    @Mock private DnSyncChunkCheckpointMapper chunkCheckpointMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private DnSyncJobDependencyMapper dependencyMapper;
    @Mock private com.datanote.domain.project.ProjectAssetCleaner projectAssetCleaner;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private com.datanote.platform.collab.EditLockService editLockService;
    @Mock private RbacService rbacService;
    @Mock private com.datanote.platform.iam.DataAclService dataAclService;
    @InjectMocks private SyncJobService service;

    @BeforeEach
    void setUpUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "n/a", Collections.emptyList()));
        lenient().when(rbacService.getUserPermsByUsername("bob")).thenReturn(Collections.emptySet());
        lenient().when(dataAclService.canAccess(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getById_deniesOtherCreatorsJob() {
        when(syncJobMapper.selectById(7L)).thenReturn(job(7L, "alice"));

        assertThrows(BusinessException.class, () -> service.getById(7L));
    }

    @Test
    void list_filtersOtherCreatorsJobs() {
        when(syncJobMapper.selectList(null)).thenReturn(Arrays.asList(
                job(1L, "bob"),
                job(2L, "alice")));

        List<DnSyncJob> jobs = service.list();

        assertEquals(1, jobs.size());
        assertEquals(1L, jobs.get(0).getId());
    }

    @Test
    void buildConnector_deniedDatasource_doesNotReadDatasourceConfig() {
        when(dataAclService.canAccess("DATASOURCE", "9")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.buildConnector(9L, "ods"));

        verify(datasourceMapper, never()).selectById(9L);
    }

    private static DnSyncJob job(Long id, String createdBy) {
        DnSyncJob job = new DnSyncJob();
        job.setId(id);
        job.setJobName("job-" + id);
        job.setCreatedBy(createdBy);
        job.setTableConfig("{}");
        job.setFieldMapping("{}");
        return job;
    }
}
