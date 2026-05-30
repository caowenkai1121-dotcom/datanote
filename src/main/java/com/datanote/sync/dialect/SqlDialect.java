package com.datanote.sync.dialect;

import com.datanote.sync.connector.ColumnDef;

import java.util.List;

/**
 * SQL 方言抽象：把写SQL、建表DDL、列类型映射的方言差异收进一个接口。
 * 新增方言 = 实现本接口 + 在 DialectFactory 注册。
 */
public interface SqlDialect {

    /** 方言名：MYSQL / DORIS / STARROCKS */
    String name();

    /** 生成写入目标表的 SQL（占位符 ?）。 */
    String writeSql(String writeMode, String db, String table, List<String> columns, List<String> pkColumns);

    /** 生成建表 DDL。 */
    String createTableDdl(String db, String table, List<ColumnDef> columns);

    /** MySQL 列类型映射到目标库类型。 */
    String mapColumnType(String mysqlColumnType);
}
