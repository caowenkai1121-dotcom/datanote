package com.datanote.domain.integration.engine;

import com.datanote.domain.integration.connector.ConnectionManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M4：Stream Load 纯逻辑——Label/CSV/URL/响应解析/HTTP端口解析。 */
public class StreamLoadWriterTest {

    @Test
    void labelOnlySafeChars() {
        String l = StreamLoadWriter.buildLabel(12L, 99L, "app.user-tbl", 3);
        assertEquals("dn_12_99_app_user_tbl_3", l);
        assertTrue(l.matches("[A-Za-z0-9_]+"));
    }

    @Test
    void csvTabSepNullAndEscape() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1, "ok", null});
        rows.add(new Object[]{2, "a\tb", "c\nd"});
        String csv = StreamLoadWriter.toCsv(rows, 3);
        String[] lines = csv.split("\n");
        assertEquals("1\tok\t\\N", lines[0]);
        // \t 和 \n 被转义
        assertEquals("2\ta\\\tb\tc\\nd", lines[1]);
    }

    @Test
    void urlFormat() {
        assertEquals("http://10.0.0.1:8030/api/ods/t1/_stream_load",
                StreamLoadWriter.buildUrl("10.0.0.1", 8030, "ods", "t1"));
    }

    @Test
    void parseSuccessAndIdempotent() {
        StreamLoadWriter.Result ok = StreamLoadWriter.parseResult("{\"Status\":\"Success\",\"NumberLoadedRows\":100}");
        assertTrue(ok.success);
        assertEquals(100, ok.loadedRows);
        // Label Already Exists 视为幂等成功
        assertTrue(StreamLoadWriter.parseResult("{\"Status\":\"Label Already Exists\"}").success);
        assertTrue(StreamLoadWriter.parseResult("{\"Status\":\"Publish Timeout\"}").success);
    }

    @Test
    void parseFailureAndGarbage() {
        assertFalse(StreamLoadWriter.parseResult("{\"Status\":\"Fail\",\"Message\":\"too many filtered rows\"}").success);
        assertFalse(StreamLoadWriter.parseResult("").success);
        assertFalse(StreamLoadWriter.parseResult("not json").success);
    }

    @Test
    void httpPortParse() {
        assertEquals(8030, ConnectionManager.parseHttpPort(null, 8030));
        assertEquals(8030, ConnectionManager.parseHttpPort("useSSL=false&a=b", 8030));
        assertEquals(8040, ConnectionManager.parseHttpPort("useSSL=false&dorisHttpPort=8040", 8030));
        assertEquals(8030, ConnectionManager.parseHttpPort("dorisHttpPort=abc", 8030));
    }
}
