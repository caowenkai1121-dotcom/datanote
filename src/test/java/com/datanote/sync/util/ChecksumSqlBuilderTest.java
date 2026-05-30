package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class ChecksumSqlBuilderTest {
    @Test void singlePk() {
        String s = ChecksumSqlBuilder.build("db","t", Arrays.asList("id","name"), Arrays.asList("id"), 16);
        assertEquals("SELECT MOD(CRC32(CONCAT_WS('#', `id`)), 16) AS bk, COUNT(*) cnt, "
            + "BIT_XOR(CRC32(CONCAT_WS('#', IFNULL(`id`,'\\0'), IFNULL(`name`,'\\0')))) chk "
            + "FROM `db`.`t` GROUP BY bk", s);
    }
    @Test void multiPk() {
        String s = ChecksumSqlBuilder.build("db","t", Arrays.asList("a","b"), Arrays.asList("a","b"), 8);
        assertTrue(s.contains("CRC32(CONCAT_WS('#', `a`, `b`))"));
        assertTrue(s.contains("GROUP BY bk"));
        assertTrue(s.contains(", 8)"));
    }
}
