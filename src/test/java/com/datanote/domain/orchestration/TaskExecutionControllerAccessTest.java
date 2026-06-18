package com.datanote.domain.orchestration;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.domain.orchestration.mapper.DnTaskExecutionMapper;
import com.datanote.domain.orchestration.model.DnTaskExecution;
import com.datanote.platform.iam.RbacService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskExecutionControllerAccessTest {

    @Mock private DnTaskExecutionMapper executionMapper;
    @Mock private DnScriptMapper scriptMapper;
    @Mock private DnSyncTaskMapper syncTaskMapper;
    @Mock private RbacService rbacService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void detail_deniesOtherCreatorsScriptExecution() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "n/a", Collections.emptyList()));
        when(rbacService.getUserPermsByUsername("bob")).thenReturn(Collections.emptySet());
        DnTaskExecution exec = new DnTaskExecution();
        exec.setId(5L);
        exec.setScriptId(9L);
        when(executionMapper.selectById(5L)).thenReturn(exec);
        DnScript script = new DnScript();
        script.setId(9L);
        script.setCreatedBy("alice");
        when(scriptMapper.selectById(9L)).thenReturn(script);

        TaskExecutionController controller = new TaskExecutionController(executionMapper, scriptMapper, syncTaskMapper, rbacService);

        assertThrows(BusinessException.class, () -> controller.detail(5L));
    }
}
