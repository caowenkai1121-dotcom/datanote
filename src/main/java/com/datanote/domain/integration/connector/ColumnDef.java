package com.datanote.domain.integration.connector;

import lombok.Data;

/**
 * 列完整定义（用于自动建表）。
 */
@Data
public class ColumnDef {
    private String name;         // 列名
    private String columnType;   // MySQL 原始类型，如 varchar(50)/int/datetime/decimal(10,2)
    private boolean nullable;    // 是否可空
    private boolean primaryKey;  // 是否主键
    private String comment;      // 注释
}
