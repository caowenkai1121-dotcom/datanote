package com.datanote.domain.integration.dialect;

/**
 * StarRocks 方言：写SQL/建表DDL/类型映射与 Doris 完全一致，仅方言名不同。
 */
public class StarRocksDialect extends DorisDialect {

    @Override
    public String name() {
        return "STARROCKS";
    }
}
