package com.datanote.service;

import com.datanote.model.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HiveServiceDorisTest {

    @Test
    void generateDDL_usesDorisOlapSyntax() {
        HiveService service = new HiveService(null, null);
        List<ColumnInfo> columns = Arrays.asList(column("User ID", "user id"), column("amount", "order amount"));

        String ddl = service.generateDDL("mall", "orders", columns, "df");

        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS ods.ods_mall_orders_df"));
        assertTrue(ddl.contains("`dt` VARCHAR(10) COMMENT 'sync date'"));
        assertTrue(ddl.contains("`user_id` STRING COMMENT 'user id'"));
        assertTrue(ddl.contains("ENGINE=OLAP"));
        assertTrue(ddl.contains("DUPLICATE KEY(`dt`)"));
        assertTrue(ddl.contains("DISTRIBUTED BY RANDOM BUCKETS 10"));
        assertTrue(ddl.contains("\"replication_num\" = \"1\""));
        assertFalse(ddl.contains("PARTITIONED BY"));
        assertFalse(ddl.contains("STORED AS ORC"));
    }

    @Test
    void generateDDLRejectsEmptySourceColumns() {
        HiveService service = new HiveService(null, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.generateDDL("xh_dms", "t_after_sales_order_header", java.util.Collections.emptyList(), "df"));
    }

    private ColumnInfo column(String name, String comment) {
        ColumnInfo column = new ColumnInfo();
        column.setName(name);
        column.setComment(comment);
        return column;
    }
}
