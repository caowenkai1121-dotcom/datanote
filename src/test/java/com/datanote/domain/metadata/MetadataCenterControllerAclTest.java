package com.datanote.domain.metadata;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.datasource.MetadataCrawlerService;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnMetaCollectLogMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnMetaCollectLog;
import com.datanote.domain.metadata.model.DnTableMeta;
import com.datanote.platform.iam.DataAclService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataCenterControllerAclTest {

    @Mock private DnTableMetaMapper tableMetaMapper;
    @Mock private DnColumnMetaMapper columnMetaMapper;
    @Mock private MetadataCrawlerService crawlerService;
    @Mock private DnMetaCollectLogMapper collectLogMapper;
    @Mock private DataAclService dataAclService;
    @InjectMocks private MetadataCenterController controller;

    @BeforeEach
    void setUpAcl() {
        lenient().when(dataAclService.canAccess(anyString(), anyString())).thenReturn(true);
        lenient().when(dataAclService.deniedIds(anyString())).thenReturn(Collections.emptySet());
    }

    @Test
    void getTableDetail_deniedByTableAcl_doesNotReadColumns() {
        DnTableMeta meta = table(8L, "ods", "orders");
        when(tableMetaMapper.selectById(8L)).thenReturn(meta);
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.getTableDetail(8L));

        verify(columnMetaMapper, never()).selectList(any());
    }

    @Test
    void searchTables_filtersDeniedTables() {
        when(tableMetaMapper.selectCount(any())).thenReturn(2L);
        when(tableMetaMapper.selectList(any())).thenReturn(Arrays.asList(
                table(1L, "ods", "visible"),
                table(2L, "ods", "secret")));
        when(dataAclService.deniedIds("TABLE")).thenReturn(Collections.singleton("ods.secret"));

        Object rows = controller.searchTables(null, null, null, null, null, null).getData().get("rows");

        assertEquals(1, ((java.util.List<?>) rows).size());
    }

    @Test
    void crawlDatasource_deniedByDatasourceAcl_doesNotCrawl() {
        when(dataAclService.canAccess("DATASOURCE", "2")).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.crawlDatasource(2L));

        verify(crawlerService, never()).crawlDatasource(anyLong());
    }

    private static DnTableMeta table(Long id, String db, String table) {
        DnTableMeta meta = new DnTableMeta();
        meta.setId(id);
        meta.setDatabaseName(db);
        meta.setTableName(table);
        return meta;
    }
}
