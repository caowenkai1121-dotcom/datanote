package com.datanote.domain.integration;

import com.datanote.common.model.R;
import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.MetadataService;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.governance.MaskingService;
import com.datanote.domain.integration.dto.HiveExecuteRequest;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.platform.iam.DataAclService;
import com.datanote.platform.iam.RbacService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HiveDdlControllerAclTest {

    @Mock private HiveService hiveService;
    @Mock private MetadataService metadataService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private DnTaskExecutionMapper taskExecutionMapper;
    @Mock private DnDatasourceMapper datasourceMapper;
    @Mock private DnSyncTaskMapper syncTaskMapper;
    @Mock private MaskingService maskingService;
    @Mock private RbacService rbacService;
    @Mock private DataAclService dataAclService;
    @InjectMocks private HiveDdlController controller;

    @Test
    void executeSql_deniedByTableAcl_doesNotExecuteHive() throws Exception {
        lenient().when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);
        HiveExecuteRequest body = new HiveExecuteRequest();
        body.setSql("select * from ods.orders");

        assertThrows(BusinessException.class, () -> controller.executeSQL(body));
        verify(hiveService, never()).executeSQL(anyString());
    }

    @Test
    void submitExecute_deniedByTableAcl_doesNotCreateExecution() {
        lenient().when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);
        lenient().doAnswer(invocation -> {
            DnTaskExecution exec = invocation.getArgument(0);
            exec.setId(99L);
            return 1;
        }).when(taskExecutionMapper).insert(any(DnTaskExecution.class));
        Map<String, Object> body = new HashMap<>();
        body.put("sql", "select * from ods.orders");

        R<Map<String, Object>> response = controller.submitExecute(body);

        assertEquals(-1, response.getCode());
        verify(taskExecutionMapper, never()).insert(any(DnTaskExecution.class));
    }
}
