package com.datanote.domain.governance;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.governance.mapper.DnClassificationLevelMapper;
import com.datanote.domain.governance.mapper.DnSensitiveRuleMapper;
import com.datanote.domain.governance.model.DnClassificationLevel;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.audit.mapper.DnLabelAuditMapper;
import com.datanote.platform.audit.model.DnLabelAudit;
import com.datanote.platform.config.HiveConfig;
import com.datanote.platform.iam.DataAclService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceAclTest {

    @Mock private DnClassificationLevelMapper levelMapper;
    @Mock private DnSensitiveRuleMapper ruleMapper;
    @Mock private DnLabelAuditMapper auditMapper;
    @Mock private DnColumnMetaMapper columnMetaMapper;
    @Mock private DnTableMetaMapper tableMetaMapper;
    @Mock private HiveConfig hiveConfig;
    @Mock private DataAclService dataAclService;
    @InjectMocks private ClassificationService service;

    @BeforeEach
    void setUpAcl() {
        lenient().when(dataAclService.canAccess(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void auditTrail_deniedByTableAcl_doesNotReadAudit() {
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.auditTrail("ods", "orders"));

        verify(tableMetaMapper, never()).selectOne(any());
        verify(auditMapper, never()).selectList(any());
    }

    @Test
    void scanTable_deniedByTableAcl_doesNotOpenConnection() throws Exception {
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.scanTable("ods", "orders"));

        verify(hiveConfig, never()).getConnection();
    }

    @Test
    void confirm_deniedByTableAcl_doesNotWriteMetadata() {
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> service.confirm("ods", "orders", "phone", "L2", "PHONE", "mallory", "manual"));

        verify(tableMetaMapper, never()).selectOne(any());
        verify(columnMetaMapper, never()).insert(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void confirm_usesCurrentUserInsteadOfRequestOperator() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", Collections.emptyList()));
        DnClassificationLevel level = new DnClassificationLevel();
        level.setLevelCode("L2");
        when(levelMapper.selectList(any())).thenReturn(Collections.singletonList(level));
        DnTableMeta tableMeta = new DnTableMeta();
        tableMeta.setId(7L);
        tableMeta.setDatabaseName("ods");
        tableMeta.setTableName("orders");
        when(tableMetaMapper.selectOne(any())).thenReturn(tableMeta);
        when(tableMetaMapper.selectById(7L)).thenReturn(tableMeta);
        when(columnMetaMapper.selectCount(any())).thenReturn(1L);

        service.confirm("ods", "orders", "phone", "L2", "PHONE", "mallory", "manual");

        ArgumentCaptor<DnLabelAudit> auditCaptor = ArgumentCaptor.forClass(DnLabelAudit.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertEquals("alice", auditCaptor.getValue().getOperator());
    }
}
