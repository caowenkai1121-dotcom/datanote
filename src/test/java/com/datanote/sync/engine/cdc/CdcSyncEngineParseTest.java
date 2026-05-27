package com.datanote.sync.engine.cdc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * CdcSyncEngine 纯逻辑单测：Debezium JSON -> 变更操作解析 + DELETE SQL 构建。
 * （真跑 binlog 需真实 MySQL，本地不强求；此处只测可单测的纯函数。）
 */
class CdcSyncEngineParseTest {

    @Test
    void parse_create_mapsToInsertWithAfter() {
        String json = "{\"payload\":{\"op\":\"c\",\"before\":null,"
                + "\"after\":{\"id\":1,\"name\":\"a\"},"
                + "\"source\":{\"db\":\"src\",\"table\":\"t_user\"}}}";
        CdcSyncEngine.ChangeOp op = CdcSyncEngine.parseChange(json);
        assertSame(CdcSyncEngine.OpType.INSERT, op.opType);
        assertEquals("src", op.sourceDb);
        assertEquals("t_user", op.sourceTable);
        assertNull(op.before);
        assertEquals(1, op.after.get("id"));
        assertEquals("a", op.after.get("name"));
    }

    @Test
    void parse_read_snapshot_mapsToInsert() {
        String json = "{\"payload\":{\"op\":\"r\",\"after\":{\"id\":9},"
                + "\"source\":{\"db\":\"src\",\"table\":\"t\"}}}";
        CdcSyncEngine.ChangeOp op = CdcSyncEngine.parseChange(json);
        assertSame(CdcSyncEngine.OpType.INSERT, op.opType);
    }

    @Test
    void parse_update_mapsToUpdateWithBeforeAndAfter() {
        String json = "{\"payload\":{\"op\":\"u\","
                + "\"before\":{\"id\":1,\"name\":\"a\"},"
                + "\"after\":{\"id\":1,\"name\":\"b\"},"
                + "\"source\":{\"db\":\"src\",\"table\":\"t\"}}}";
        CdcSyncEngine.ChangeOp op = CdcSyncEngine.parseChange(json);
        assertSame(CdcSyncEngine.OpType.UPDATE, op.opType);
        assertEquals("a", op.before.get("name"));
        assertEquals("b", op.after.get("name"));
    }

    @Test
    void parse_delete_mapsToDeleteWithBefore() {
        String json = "{\"payload\":{\"op\":\"d\","
                + "\"before\":{\"id\":5,\"name\":\"x\"},\"after\":null,"
                + "\"source\":{\"db\":\"src\",\"table\":\"t\"}}}";
        CdcSyncEngine.ChangeOp op = CdcSyncEngine.parseChange(json);
        assertSame(CdcSyncEngine.OpType.DELETE, op.opType);
        assertEquals(5, op.before.get("id"));
        assertNull(op.after);
    }

    @Test
    void parse_unsupportedOp_returnsNull() {
        // truncate（t）/ message（m）等非增删改操作应被跳过
        String json = "{\"payload\":{\"op\":\"t\",\"source\":{\"db\":\"src\",\"table\":\"t\"}}}";
        assertNull(CdcSyncEngine.parseChange(json));
    }

    @Test
    void parse_nullOp_returnsNull() {
        String json = "{\"payload\":{\"before\":null,\"after\":null}}";
        assertNull(CdcSyncEngine.parseChange(json));
    }

    @Test
    void parse_withoutPayloadEnvelope_treatsRootAsPayload() {
        // 无 schema 的 envelope：根即 payload
        String json = "{\"op\":\"c\",\"after\":{\"id\":2},"
                + "\"source\":{\"db\":\"src\",\"table\":\"t\"}}";
        CdcSyncEngine.ChangeOp op = CdcSyncEngine.parseChange(json);
        assertSame(CdcSyncEngine.OpType.INSERT, op.opType);
        assertEquals(2, op.after.get("id"));
    }

    @Test
    void buildDeleteSql_singlePk() {
        String sql = CdcSyncEngine.buildDeleteSql("dst", "t_user", Collections.singletonList("id"));
        assertEquals("DELETE FROM `dst`.`t_user` WHERE `id` = ?", sql);
    }

    @Test
    void buildDeleteSql_compositePk() {
        String sql = CdcSyncEngine.buildDeleteSql("dst", "t_order", Arrays.asList("oid", "sku"));
        assertEquals("DELETE FROM `dst`.`t_order` WHERE `oid` = ? AND `sku` = ?", sql);
    }
}
