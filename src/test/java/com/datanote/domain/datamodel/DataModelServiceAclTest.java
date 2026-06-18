package com.datanote.domain.datamodel;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datamodel.mapper.*;
import com.datanote.domain.datamodel.model.DnModelEntity;
import com.datanote.platform.iam.DataAclService;
import com.datanote.platform.notify.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataModelServiceAclTest {

    @Mock private DnModelMapper modelMapper;
    @Mock private DnModelEntityMapper entityMapper;
    @Mock private DnModelAttributeMapper attrMapper;
    @Mock private DnModelRelationMapper relMapper;
    @Mock private DnModelChangeMapper changeMapper;
    @Mock private NotificationService notificationService;
    @Mock private com.datanote.domain.metadata.mapper.DnTableMetaMapper tableMetaMapper;
    @Mock private com.datanote.domain.metadata.mapper.DnColumnMetaMapper columnMetaMapper;
    @Mock private com.datanote.platform.collab.EditLockService editLockService;
    @Mock private DnModelVersionMapper versionMapper;
    @Mock private com.datanote.domain.governance.mapper.DnDataElementMapper dataElementMapper;
    @Mock private com.datanote.domain.governance.mapper.DnWordRootMapper wordRootMapper;
    @Mock private com.datanote.domain.governance.mapper.DnQualityRuleMapper qualityRuleMapper;
    @Mock private DataAclService dataAclService;
    @InjectMocks private DataModelService service;

    @BeforeEach
    void setUpAcl() {
        lenient().when(dataAclService.canAccess(anyString(), anyString())).thenReturn(true);
        lenient().when(dataAclService.deniedIds(anyString())).thenReturn(java.util.Collections.emptySet());
    }

    @Test
    void getModelDetail_deniedByModelAcl_doesNotReadModel() {
        when(dataAclService.canAccess("MODEL", "7")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.getModelDetail(7L));

        verify(modelMapper, never()).selectById(7L);
        verify(entityMapper, never()).selectList(any());
    }

    @Test
    void saveEntity_deniedByModelAcl_doesNotInsertEntity() {
        when(dataAclService.canAccess("MODEL", "7")).thenReturn(false);
        DnModelEntity entity = new DnModelEntity();
        entity.setModelId(7L);
        entity.setEntityCode("OrderEntity");

        assertThrows(BusinessException.class, () -> service.saveEntity(entity));

        verify(entityMapper, never()).insert(any());
        verify(entityMapper, never()).updateById(any());
    }
}
