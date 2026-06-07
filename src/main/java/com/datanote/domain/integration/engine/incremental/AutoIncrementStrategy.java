package com.datanote.domain.integration.engine.incremental;

import java.math.BigDecimal;

/**
 * 自增 ID 增量策略：按数值比较。
 */
public class AutoIncrementStrategy implements IncrementalStrategy {

    @Override
    public String type() {
        return "AUTO_INCREMENT";
    }

    @Override
    public int compare(Object a, Object b) {
        return new BigDecimal(String.valueOf(a)).compareTo(new BigDecimal(String.valueOf(b)));
    }

    @Override
    public String toStored(Object value) {
        return String.valueOf(value);
    }
}
