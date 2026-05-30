package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DS-M9：SQL Server T-SQL 方言 SQL / 类型归一 / URL。 */
public class SqlServerConnectorTest {

    private final SqlServerConnector ss = new SqlServerConnector(null, 1L, "dbo");
    private final List<String> cols = Arrays.asList("id", "name");
    private final List<String> pk = Arrays.asList("id");

    @Test
    void keysetBracketQuotingAndOffsetFetch() {
        String sql = ss.keysetPageSql("dbo", "users", cols, pk, true, null);
        assertEquals("SELECT [id], [name] FROM [dbo].[users] WHERE [id] > ? ORDER BY [id] ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY", sql);
    }

    @Test
    void keysetNoCursor() {
        String sql = ss.keysetPageSql("dbo", "users", cols, pk, false, null);
        assertEquals("SELECT [id], [name] FROM [dbo].[users] ORDER BY [id] ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY", sql);
    }

    @Test
    void incrementalCursor() {
        String sql = ss.incrementalPageSql("dbo", "t", cols, "updated_at", pk, false, null);
        assertTrue(sql.contains("([updated_at] > ? OR ([updated_at] = ? AND [id] > ?))"));
        assertTrue(sql.endsWith("OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"));
    }

    @Test
    void compositePkUnsupported() {
        assertThrows(IllegalStateException.class,
                () -> ss.keysetPageSql("dbo", "t", cols, Arrays.asList("a", "b"), true, null));
    }

    @Test
    void countAndScan() {
        assertEquals("SELECT COUNT(*) FROM [dbo].[users]", ss.countSql("dbo", "users", null));
        assertEquals("SELECT [id], [name] FROM [dbo].[t] WHERE x>1", ss.scanSql("dbo", "t", cols, "x>1"));
    }

    @Test
    void typeNormalization() {
        assertEquals("int", SqlServerConnector.sqlServerTypeToMysql("int", null, null, null));
        assertEquals("bigint", SqlServerConnector.sqlServerTypeToMysql("bigint", null, null, null));
        assertEquals("tinyint(1)", SqlServerConnector.sqlServerTypeToMysql("bit", null, null, null));
        assertEquals("varchar(50)", SqlServerConnector.sqlServerTypeToMysql("nvarchar", 50, null, null));
        assertEquals("text", SqlServerConnector.sqlServerTypeToMysql("nvarchar", -1, null, null));
        assertEquals("decimal(18,2)", SqlServerConnector.sqlServerTypeToMysql("decimal", null, 18, 2));
        assertEquals("datetime", SqlServerConnector.sqlServerTypeToMysql("datetime2", null, null, null));
        assertEquals("decimal(19,4)", SqlServerConnector.sqlServerTypeToMysql("money", null, null, null));
        assertEquals("varchar(64)", SqlServerConnector.sqlServerTypeToMysql("uniqueidentifier", null, null, null));
    }

    @Test
    void url() {
        assertEquals("jdbc:sqlserver://h:1433;databaseName=mydb;encrypt=false;trustServerCertificate=true;loginTimeout=10",
                ConnectionManager.buildSqlServerUrl("h", 1433, "mydb", null));
    }
}
