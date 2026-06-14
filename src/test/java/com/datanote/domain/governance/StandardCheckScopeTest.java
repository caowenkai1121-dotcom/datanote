package com.datanote.domain.governance;

import com.datanote.domain.governance.mapper.DnDataElementMapper;
import com.datanote.domain.governance.mapper.DnStandardCheckRunMapper;
import com.datanote.domain.governance.mapper.DnWordRootMapper;
import com.datanote.domain.governance.model.DnStandardCheckRun;
import com.datanote.domain.metadata.mapper.DnColumnMetaMapper;
import com.datanote.domain.metadata.mapper.DnTableMetaMapper;
import com.datanote.domain.metadata.model.DnColumnMeta;
import com.datanote.domain.metadata.model.DnTableMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 落标稽核范围过滤单测 —— 选 db/db.table 时只稽核该范围的列(修复原 scope 仅入库未生效)。
 */
@ExtendWith(MockitoExtension.class)
class StandardCheckScopeTest {

    @Mock private DnColumnMetaMapper columnMetaMapper;
    @Mock private DnDataElementMapper dataElementMapper;
    @Mock private DnWordRootMapper wordRootMapper;
    @Mock private DnStandardCheckRunMapper checkRunMapper;
    @Mock private DnTableMetaMapper tableMetaMapper;

    private StandardService svc() {
        return new StandardService(columnMetaMapper, dataElementMapper, wordRootMapper, checkRunMapper, tableMetaMapper);
    }

    private DnColumnMeta col(long tableMetaId, String name) {
        DnColumnMeta c = new DnColumnMeta();
        c.setTableMetaId(tableMetaId);
        c.setColumnName(name);
        c.setDataType("varchar(10)");
        return c;
    }

    @Test
    void runCheck_scopeFiltersColumnsToSelectedTable() {
        when(columnMetaMapper.selectList(null)).thenReturn(Arrays.asList(col(1, "a"), col(2, "b"), col(1, "c")));
        DnTableMeta t = new DnTableMeta();
        t.setId(1L);
        when(tableMetaMapper.selectList(any())).thenReturn(Collections.singletonList(t));

        DnStandardCheckRun run = svc().runCheck("db1.t1");
        assertEquals(2, run.getTotalCount(), "仅 tableMetaId=1 的两列纳入稽核");
        assertEquals("db1.t1", run.getScope());
    }

    @Test
    void runCheck_emptyScope_checksAllColumnsAndSkipsTableLookup() {
        when(columnMetaMapper.selectList(null)).thenReturn(Arrays.asList(col(1, "a"), col(2, "b")));

        DnStandardCheckRun run = svc().runCheck("");
        assertEquals(2, run.getTotalCount());
        assertEquals("all", run.getScope());
        verify(tableMetaMapper, never()).selectList(any());
    }

    @Test
    void runCheck_scopeNoMatchingTable_yieldsZeroTotal() {
        when(columnMetaMapper.selectList(null)).thenReturn(Arrays.asList(col(1, "a"), col(2, "b")));
        when(tableMetaMapper.selectList(any())).thenReturn(Collections.emptyList());

        DnStandardCheckRun run = svc().runCheck("ghost_db");
        assertEquals(0, run.getTotalCount(), "范围无匹配表则无列可稽核");
    }
}
