package com.datanote.service;

import com.datanote.model.DnColumnMeta;
import com.datanote.model.DnTableMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataCrawlerMergeTest {

    @Test
    void mergeTableUpdatesTechnicalFields() {
        DnTableMeta t = new DnTableMeta();
        MetadataCrawlerService.mergeTableTechnical(t, "DORIS", "BASE TABLE", 1234L, 5678L, "订单表");
        assertEquals("DORIS", t.getDbType());
        assertEquals("BASE TABLE", t.getTableType());
        assertEquals(1234L, t.getRowCount());
        assertEquals(5678L, t.getSizeBytes());
        assertEquals("订单表", t.getTableComment());
    }

    @Test
    void mergeTablePreservesManualCommentAndNullRows() {
        DnTableMeta t = new DnTableMeta();
        t.setTableComment("人工业务描述");
        t.setRowCount(99L);
        MetadataCrawlerService.mergeTableTechnical(t, "MYSQL", "BASE TABLE", null, 10L, "DB注释");
        assertEquals("人工业务描述", t.getTableComment(), "已有业务描述不应被 DB 注释覆盖");
        assertEquals(99L, t.getRowCount(), "rows 为 null 时保留原值");
        assertEquals(10L, t.getSizeBytes());
    }

    @Test
    void mergeColumnUpdatesTechnicalAndFillsBlankDesc() {
        DnColumnMeta c = new DnColumnMeta();
        MetadataCrawlerService.mergeColumnTechnical(c, "varchar(50)", "PRI", "NO", 1, "用户名");
        assertEquals("varchar(50)", c.getDataType());
        assertEquals("PRI", c.getColumnKey());
        assertEquals("NO", c.getIsNullable());
        assertEquals(1, c.getOrdinal());
        assertEquals("用户名", c.getBusinessDesc());
    }

    @Test
    void mergeColumnPreservesManualDesc() {
        DnColumnMeta c = new DnColumnMeta();
        c.setBusinessDesc("人工填写");
        MetadataCrawlerService.mergeColumnTechnical(c, "int", "", "YES", 2, "DB注释");
        assertEquals("人工填写", c.getBusinessDesc());
        assertEquals("int", c.getDataType());
    }

    @Test
    void mergeTableBlankCommentFillsFromDb() {
        DnTableMeta t = new DnTableMeta();
        t.setTableComment("");
        MetadataCrawlerService.mergeTableTechnical(t, "MYSQL", "BASE TABLE", 1L, 1L, "来自DB");
        assertEquals("来自DB", t.getTableComment());
        assertNull(new DnTableMeta().getOwner());
    }
}
