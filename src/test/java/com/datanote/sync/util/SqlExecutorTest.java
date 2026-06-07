package com.datanote.domain.integration.util;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
public class SqlExecutorTest {
    @Test public void blankGivesEmpty() {
        assertTrue(SqlExecutor.splitStatements(null).isEmpty());
        assertTrue(SqlExecutor.splitStatements("   ").isEmpty());
    }
    @Test public void singleStatement() {
        List<String> s = SqlExecutor.splitStatements("TRUNCATE TABLE t");
        assertEquals(1, s.size());
        assertEquals("TRUNCATE TABLE t", s.get(0));
    }
    @Test public void multiSplitOnSemicolon() {
        List<String> s = SqlExecutor.splitStatements("TRUNCATE t1;\nDELETE FROM t2 WHERE x=1;");
        assertEquals(2, s.size());
        assertEquals("DELETE FROM t2 WHERE x=1", s.get(1));
    }
    @Test public void commentLinesSkipped() {
        List<String> s = SqlExecutor.splitStatements("-- clean\nTRUNCATE t1;\n-- next\nDELETE FROM t2");
        assertEquals(2, s.size());
        assertEquals("TRUNCATE t1", s.get(0));
        assertEquals("DELETE FROM t2", s.get(1));
    }
}
