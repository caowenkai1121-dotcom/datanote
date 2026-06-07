package com.datanote.domain.integration.connector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 数据库连接器（第一版仅 MySQL 协议族实现）。
 */
public interface DbConnector {

    /** MYSQL / DORIS / STARROCKS */
    String getDatabaseType();

    /** 从连接池获取连接，调用方负责 close。 */
    Connection getConnection() throws SQLException;

    /** 获取表的列与主键。 */
    TableMeta getTableMeta(String db, String table) throws SQLException;

    /** 获取库下所有表。 */
    List<String> listTables(String db) throws SQLException;

    /** 获取表的完整列定义（用于自动建表）。 */
    List<ColumnDef> getColumnDefs(String db, String table) throws SQLException;

    // ===== DS-M8：源端读取方言 SQL（各连接器按本库语法实现，跨库适配） =====

    /** 无主键全表扫描 SQL。 */
    String scanSql(String db, String table, List<String> columns, String extraWhere);

    /** 复合主键 keyset 分页 SQL（hasCursor 时带行值游标，末尾 LIMIT ?）。 */
    String keysetPageSql(String db, String table, List<String> columns,
                         List<String> pkColumns, boolean hasCursor, String extraWhere);

    /** 复合游标增量分页 SQL（(incField, pk..) 游标，末尾 LIMIT ?）。 */
    String incrementalPageSql(String db, String table, List<String> columns,
                              String incField, List<String> pkColumns, boolean firstPage, String extraWhere);

    /** COUNT SQL（extraWhere 非空才接 WHERE）。 */
    String countSql(String db, String table, String extraWhere);

    /** 按本库方言引用标识符（MySQL/Doris 反引号、PG/Oracle 双引号、SQLServer 方括号）。 */
    String quoteIdentifier(String id);
}
