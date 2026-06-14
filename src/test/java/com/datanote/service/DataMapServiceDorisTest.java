package com.datanote.service;

import com.datanote.domain.datasource.DatasourceExploreService;
import com.datanote.domain.metadata.DataMapService;
import com.datanote.platform.ai.AiAssistService;
import com.datanote.platform.config.HiveConfig;
import com.datanote.platform.portal.mapper.DnSearchHistoryMapper;
import com.datanote.domain.metadata.mapper.DnTableCommentMapper;
import com.datanote.domain.metadata.mapper.DnTableFavoriteMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.ColumnInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R5 拆分后：在线探查走 DatasourceExploreService，离线表详情走 DataMapService(委托 explore)。
 * 行为保持：仍用 Doris information_schema 取列/表信息，不走 Hive DESCRIBE FORMATTED。
 */
@ExtendWith(MockitoExtension.class)
class DataMapServiceDorisTest {

    @Mock private AiAssistService aiAssistService;
    @Mock private HiveConfig hiveConfig;
    @Mock private DnTableCommentMapper tableCommentMapper;
    @Mock private DnTableFavoriteMapper tableFavoriteMapper;
    @Mock private DnSearchHistoryMapper searchHistoryMapper;
    @Mock private DnTableMetaMapper tableMetaMapper;

    @Test
    void exploreGetHiveColumnsReadsDorisColumnCommentsFromInformationSchema() throws Exception {
        Connection conn = org.mockito.Mockito.mock(Connection.class);
        PreparedStatement stmt = org.mockito.Mockito.mock(PreparedStatement.class);
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);

        when(hiveConfig.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("COLUMN_NAME")).thenReturn("after_sales_code");
        when(rs.getString("COLUMN_TYPE")).thenReturn("string");
        when(rs.getString("COLUMN_COMMENT")).thenReturn("售后单号");
        when(rs.getString("IS_NULLABLE")).thenReturn("YES");
        when(rs.getString("COLUMN_KEY")).thenReturn("");
        when(rs.getString("EXTRA")).thenReturn("");

        DatasourceExploreService explore = new DatasourceExploreService(hiveConfig, org.mockito.Mockito.mock(com.datanote.domain.governance.MaskingService.class));
        List<ColumnInfo> columns = explore.getHiveColumns("ods", "ods_xh_dms_t_after_sales_order_detail_df");

        assertEquals(1, columns.size());
        assertEquals("after_sales_code", columns.get(0).getName());
        assertEquals("string", columns.get(0).getType());
        assertEquals("售后单号", columns.get(0).getComment());
        verify(conn, never()).createStatement();
    }

    @Test
    void getTableDetailUsesDorisInformationSchemaInsteadOfHiveDescribeFormatted() throws Exception {
        Connection tableConn = org.mockito.Mockito.mock(Connection.class);
        Connection columnConn = org.mockito.Mockito.mock(Connection.class);
        PreparedStatement tableStmt = org.mockito.Mockito.mock(PreparedStatement.class);
        PreparedStatement columnStmt = org.mockito.Mockito.mock(PreparedStatement.class);
        ResultSet tableRs = org.mockito.Mockito.mock(ResultSet.class);
        ResultSet columnRs = org.mockito.Mockito.mock(ResultSet.class);

        when(hiveConfig.getConnection()).thenReturn(tableConn, columnConn);
        when(tableConn.prepareStatement(contains("information_schema.TABLES"))).thenReturn(tableStmt);
        when(tableStmt.executeQuery()).thenReturn(tableRs);
        when(tableRs.next()).thenReturn(true);
        when(tableRs.getString("TABLE_NAME")).thenReturn("ods_xh_dms_t_after_sales_order_detail_df");
        when(tableRs.getString("TABLE_COMMENT")).thenReturn("ODS xh_dms.t_after_sales_order_detail sync");
        when(tableRs.getString("ENGINE")).thenReturn("Doris");
        when(tableRs.getObject("TABLE_ROWS")).thenReturn(674L);
        when(tableRs.getObject("CREATE_TIME")).thenReturn("2026-05-26 12:29:52");
        when(tableRs.getObject("UPDATE_TIME")).thenReturn("2026-05-26 12:30:17");

        when(columnConn.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(columnStmt);
        when(columnStmt.executeQuery()).thenReturn(columnRs);
        when(columnRs.next()).thenReturn(true, false);
        when(columnRs.getString("COLUMN_NAME")).thenReturn("dt");
        when(columnRs.getString("COLUMN_TYPE")).thenReturn("varchar(10)");
        when(columnRs.getString("COLUMN_COMMENT")).thenReturn("sync date");
        when(columnRs.getString("IS_NULLABLE")).thenReturn("YES");
        when(columnRs.getString("COLUMN_KEY")).thenReturn("DUP");
        when(columnRs.getString("EXTRA")).thenReturn("");
        when(tableFavoriteMapper.selectCount(any())).thenReturn(0L);

        DataMapService service = newService();
        Map<String, Object> detail = service.getTableDetail("ods", "ods_xh_dms_t_after_sales_order_detail_df");

        Map<?, ?> info = (Map<?, ?>) detail.get("tableInfo");
        assertEquals("ods_xh_dms_t_after_sales_order_detail_df", info.get("table"));
        assertEquals("ODS xh_dms.t_after_sales_order_detail sync", info.get("comment"));
        assertEquals(674L, info.get("rowCount"));
        assertEquals("Doris", info.get("engine"));
        assertFalse((Boolean) detail.get("favorited"));
        assertEquals(1, ((List<?>) detail.get("columns")).size());
        verify(tableConn, never()).createStatement();
        verify(tableConn, never()).prepareStatement(contains("DESCRIBE FORMATTED"));
        verify(columnConn, never()).createStatement();
    }

    private DataMapService newService() {
        return new DataMapService(
                aiAssistService,
                tableCommentMapper,
                tableFavoriteMapper,
                searchHistoryMapper,
                tableMetaMapper,
                org.mockito.Mockito.mock(com.datanote.domain.metadata.mapper.DnColumnMetaMapper.class),
                new DatasourceExploreService(hiveConfig, org.mockito.Mockito.mock(com.datanote.domain.governance.MaskingService.class))
        );
    }
}
