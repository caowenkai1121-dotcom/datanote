package com.datanote.domain.governance;

import com.datanote.common.exception.BusinessException;
import com.datanote.domain.governance.mapper.DnGlossaryTermMapper;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.platform.config.HiveConfig;
import com.datanote.platform.iam.DataAclService;
import com.datanote.domain.governance.AssetDetailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 资产详情纯函数单测 —— Profiler 空值率格式化 / 字段限流，无 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class AssetDetailServiceTest {

    @Mock private DnTableMetaMapper tableMetaMapper;
    @Mock private DnColumnMetaMapper columnMetaMapper;
    @Mock private DnGlossaryTermMapper glossaryTermMapper;
    @Mock private HiveConfig hiveConfig;
    @Mock private com.datanote.domain.integration.connector.ConnectionManager connectionManager;
    @Mock private com.datanote.platform.ai.vector.SemanticSearchService semanticSearchService;
    @Mock private com.datanote.platform.ai.vector.VectorIndexService vectorIndexService;
    @Mock private DataAclService dataAclService;
    @InjectMocks private AssetDetailService service;

    @BeforeEach
    void setUpAcl() {
        lenient().when(dataAclService.canAccess(anyString(), anyString())).thenReturn(true);
    }

    // ========== 空值率格式化 ==========

    @Test
    void formatRateZeroWhenTotalZero() {
        assertEquals("0%", AssetDetailService.formatRate(0, 0));
        assertEquals("0%", AssetDetailService.formatRate(5, 0));
    }

    @Test
    void formatRateOneDecimal() {
        // 3/8 = 37.5%
        assertEquals("37.5%", AssetDetailService.formatRate(3, 8));
        // 0/10 = 0.0%
        assertEquals("0.0%", AssetDetailService.formatRate(0, 10));
        // 全空 10/10 = 100.0%
        assertEquals("100.0%", AssetDetailService.formatRate(10, 10));
    }

    // ========== Profiler 字段限流 ==========

    @Test
    void limitFieldsCapsAtMax() {
        assertEquals(30, AssetDetailService.limitFields(100, 30));
        assertEquals(30, AssetDetailService.limitFields(30, 30));
    }

    @Test
    void limitFieldsKeepsWhenBelowMax() {
        assertEquals(5, AssetDetailService.limitFields(5, 30));
        assertEquals(0, AssetDetailService.limitFields(0, 30));
        assertEquals(0, AssetDetailService.limitFields(-3, 30));
    }

    @Test
    void assetDetail_deniedByTableAcl_doesNotReadMetadata() {
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.assetDetail("ods", "orders"));

        verify(tableMetaMapper, never()).selectOne(any());
    }

    @Test
    void profile_deniedByTableAcl_doesNotOpenConnection() throws Exception {
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.profile("ods", "orders"));

        verify(hiveConfig, never()).getConnection();
    }

    @Test
    void readRows_deniedByTableAcl_doesNotOpenConnection() throws Exception {
        when(dataAclService.canAccess("TABLE", "ods.orders")).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.readRows("ods", "orders", 10));

        verify(hiveConfig, never()).getConnection();
        verify(connectionManager, never()).getConnection(anyLong(), anyString());
    }
}
