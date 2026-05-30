package com.datanote.sync.service;

import com.datanote.mapper.DnCdcDeadLetterMapper;
import com.datanote.mapper.DnCdcOffsetMapper;
import com.datanote.mapper.DnCdcSchemaHistoryMapper;
import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.ConnectionManager;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.schema.TableSchemaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CdcEngineManagerTest {

    @Test
    void ensureTargetTablesAppliesFieldMappingAndSyncTimestampColumn() throws Exception {
        SyncJobService syncJobService = mock(SyncJobService.class);
        TableSchemaService tableSchemaService = mock(TableSchemaService.class);
        CdcEngineManager manager = new CdcEngineManager(
                mock(DnCdcOffsetMapper.class),
                mock(DnCdcSchemaHistoryMapper.class),
                mock(DnSyncJobMapper.class),
                mock(DnDatasourceMapper.class),
                mock(ConnectionManager.class),
                mock(LogBroadcastService.class),
                syncJobService,
                tableSchemaService,
                mock(DnTaskExecutionMapper.class),
                mock(DnCdcDeadLetterMapper.class));

        DnSyncJob job = new DnSyncJob();
        job.setId(7L);
        job.setSourceDsId(1L);
        job.setTargetDsId(2L);
        job.setSourceDb("src");
        job.setTargetDb("ods");
        job.setMarkSyncTs(1);
        job.setSyncTsField("_sync_ts");

        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable("orders");
        tc.setTargetTable("ods_orders");
        tc.setCreateTargetTable(Boolean.TRUE);
        tc.setFields(Arrays.asList(
                field("id", "order_id", true),
                field("name", "customer_name", true),
                field("age", "age", false)));

        DbConnector source = mock(DbConnector.class);
        DbConnector target = mock(DbConnector.class);
        when(syncJobService.buildConnector(1L, "src")).thenReturn(source);
        when(syncJobService.buildConnector(2L, "ods")).thenReturn(target);
        when(source.getColumnDefs("src", "orders")).thenReturn(Arrays.asList(
                column("id", "bigint", false, true),
                column("name", "varchar(64)", true, false),
                column("age", "int", true, false)));

        ReflectionTestUtils.invokeMethod(manager, "ensureTargetTables", job, Arrays.asList(tc));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<ColumnDef>> columnsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(tableSchemaService).ensureTargetTable(eq(target), eq("ods"), eq("ods_orders"), columnsCaptor.capture());

        List<ColumnDef> columns = columnsCaptor.getValue();
        assertEquals(Arrays.asList("order_id", "customer_name", "_sync_ts"),
                columns.stream().map(ColumnDef::getName).collect(Collectors.toList()));
        assertTrue(columns.get(0).isPrimaryKey());
        assertEquals("DATETIME", columns.get(2).getColumnType());
        assertTrue(columns.get(2).isNullable());
        assertFalse(columns.get(2).isPrimaryKey());
    }

    private static FieldMapping field(String source, String target, boolean sync) {
        FieldMapping field = new FieldMapping();
        field.setSource(source);
        field.setTarget(target);
        field.setSync(sync);
        return field;
    }

    private static ColumnDef column(String name, String type, boolean nullable, boolean primaryKey) {
        ColumnDef column = new ColumnDef();
        column.setName(name);
        column.setColumnType(type);
        column.setNullable(nullable);
        column.setPrimaryKey(primaryKey);
        return column;
    }
}
