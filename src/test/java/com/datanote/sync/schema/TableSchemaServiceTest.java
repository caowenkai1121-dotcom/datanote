package com.datanote.sync.schema;

import com.datanote.sync.connector.ColumnDef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSchemaServiceTest {

    private final TableSchemaService svc = new TableSchemaService(new TypeMappingService());

    private List<ColumnDef> cols() {
        ColumnDef id = new ColumnDef();
        id.setName("id"); id.setColumnType("bigint"); id.setNullable(false); id.setPrimaryKey(true); id.setComment("主键");
        ColumnDef name = new ColumnDef();
        name.setName("name"); name.setColumnType("varchar(50)"); name.setNullable(true); name.setPrimaryKey(false); name.setComment("");
        return Arrays.asList(id, name);
    }

    @Test
    void mysqlDdl_copiesSourceTypes_withPrimaryKey() {
        String ddl = svc.buildDdl("MYSQL", "dst", "t_user", cols());
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `dst`.`t_user` (\n"
            + "  `id` bigint NOT NULL COMMENT '主键',\n"
            + "  `name` varchar(50) NULL,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            ddl);
    }

    @Test
    void dorisDdl_usesUniqueKeyModel() {
        String ddl = svc.buildDdl("DORIS", "dst", "t_user", cols());
        assertTrue(ddl.contains("`id` BIGINT"), ddl);
        assertTrue(ddl.contains("`name` VARCHAR(150)"), ddl);
        assertTrue(ddl.contains("UNIQUE KEY(`id`)"), ddl);
        assertTrue(ddl.contains("DISTRIBUTED BY HASH(`id`) BUCKETS 10"), ddl);
        assertTrue(ddl.contains("\"replication_num\" = \"1\""), ddl);
    }

    @Test
    void noPrimaryKey_throws() {
        ColumnDef c = new ColumnDef();
        c.setName("v"); c.setColumnType("int"); c.setNullable(true); c.setPrimaryKey(false);
        try {
            svc.buildDdl("DORIS", "dst", "t", Collections.singletonList(c));
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
