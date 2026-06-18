package com.datanote.domain.develop;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.develop.mapper.DnScriptMapper;
import com.datanote.domain.develop.mapper.DnScriptVersionMapper;
import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.mapper.DnSyncTaskMapper;
import com.datanote.platform.collab.EditLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScriptServiceAccessTest {

    @Mock private DnScriptMapper scriptMapper;
    @Mock private DnScriptVersionMapper scriptVersionMapper;
    @Mock private DnSyncTaskMapper syncTaskMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EditLockService editLockService;
    @Mock private com.datanote.platform.iam.RbacService rbacService;
    @Mock private com.datanote.platform.iam.DataAclService dataAclService;

    private ScriptService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob", "n/a", Collections.emptyList()));
        lenient().when(rbacService.getUserPermsByUsername("bob")).thenReturn(Collections.emptySet());
        service = new ScriptService(null, scriptMapper, null, scriptVersionMapper, syncTaskMapper,
                null, eventPublisher, editLockService, rbacService, dataAclService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getById_deniesOtherCreatorsScript() {
        when(scriptMapper.selectById(7L)).thenReturn(script(7L, "alice"));

        assertThrows(BusinessException.class, () -> service.getById(7L));
    }

    @Test
    void allWithContent_filtersOtherCreatorsScripts() {
        when(scriptMapper.selectList(null)).thenReturn(Arrays.asList(
                script(1L, "bob"),
                script(2L, "alice")));
        when(syncTaskMapper.selectList(null)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> rows = service.allWithContent();

        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("id"));
        assertEquals("select 1", rows.get(0).get("content"));
    }

    @Test
    void save_existingDenied_doesNotUpdate() {
        DnScript update = new DnScript();
        update.setId(7L);
        when(scriptMapper.selectById(7L)).thenReturn(script(7L, "alice"));

        assertThrows(BusinessException.class, () -> service.save(update));
        verify(scriptMapper, never()).updateById(any());
    }

    private static DnScript script(Long id, String createdBy) {
        DnScript script = new DnScript();
        script.setId(id);
        script.setScriptName("script-" + id);
        script.setContent("select " + id);
        script.setCreatedBy(createdBy);
        return script;
    }
}
