package com.datanote.domain.datasource;

import com.datanote.common.Constants;
import com.datanote.common.exception.BusinessException;
import com.datanote.common.model.R;
import com.datanote.domain.datasource.mapper.DnDatasourceMapper;
import com.datanote.domain.datasource.model.DnDatasource;
import com.datanote.domain.integration.connector.ConnectionManager;
import com.datanote.platform.iam.DataAclService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasourceControllerAclTest {

    @Mock private DnDatasourceMapper datasourceMapper;
    @Mock private MetadataService metadataService;
    @Mock private MetadataCrawlerService metadataCrawlerService;
    @Mock private ConnectionManager connectionManager;
    @Mock private com.datanote.domain.project.ProjectAssetCleaner projectAssetCleaner;
    @Mock private DataAclService dataAclService;
    @InjectMocks private DatasourceController controller;

    @BeforeEach
    void setUpAcl() {
        lenient().when(dataAclService.canAccess(anyString(), anyString())).thenReturn(true);
        lenient().when(dataAclService.deniedIds(anyString())).thenReturn(Collections.emptySet());
    }

    @Test
    void list_filtersDeniedDatasource() {
        DnDatasource visible = datasource(1L, "visible");
        DnDatasource denied = datasource(2L, "denied");
        when(datasourceMapper.selectList(null)).thenReturn(Arrays.asList(visible, denied));
        when(dataAclService.deniedIds("DATASOURCE")).thenReturn(Collections.singleton("2"));

        R<List<DnDatasource>> response = controller.list();

        assertEquals(1, response.getData().size());
        assertEquals(1L, response.getData().get(0).getId());
        assertEquals(Constants.PASSWORD_MASK, response.getData().get(0).getPassword());
    }

    @Test
    void getById_deniedByAcl_throwsBusinessException() {
        when(datasourceMapper.selectById(2L)).thenReturn(datasource(2L, "denied"));
        when(dataAclService.canAccess("DATASOURCE", "2")).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.getById(2L));
    }

    @Test
    void delete_deniedByAcl_doesNotDelete() {
        when(dataAclService.canAccess("DATASOURCE", "2")).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.delete(2L));

        verify(metadataCrawlerService, never()).deleteByDatasource(anyLong());
        verify(datasourceMapper, never()).deleteById(anyLong());
        verify(connectionManager, never()).evict(anyLong());
    }

    @Test
    void databases_deniedByAcl_doesNotOpenConnection() throws Exception {
        when(dataAclService.canAccess("DATASOURCE", "2")).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.databases(2L));

        verify(metadataService, never()).getDatabasesByConnection(any(), any(), anyInt(), any(), any(), any());
    }

    private static DnDatasource datasource(Long id, String name) {
        DnDatasource ds = new DnDatasource();
        ds.setId(id);
        ds.setName(name);
        ds.setPassword("encrypted");
        return ds;
    }
}
