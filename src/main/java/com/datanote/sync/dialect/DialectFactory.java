package com.datanote.sync.dialect;

/**
 * 按目标库类型选择方言。DORIS→Doris，STARROCKS→StarRocks，其余/null→MySQL。
 */
public final class DialectFactory {

    private static final SqlDialect MYSQL = new MysqlDialect();
    private static final SqlDialect DORIS = new DorisDialect();
    private static final SqlDialect STARROCKS = new StarRocksDialect();

    private DialectFactory() {
    }

    public static SqlDialect of(String type) {
        if ("DORIS".equalsIgnoreCase(type)) {
            return DORIS;
        }
        if ("STARROCKS".equalsIgnoreCase(type)) {
            return STARROCKS;
        }
        return MYSQL;
    }
}
