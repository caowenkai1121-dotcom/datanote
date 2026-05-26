package com.datanote.sync.connector;

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
}
