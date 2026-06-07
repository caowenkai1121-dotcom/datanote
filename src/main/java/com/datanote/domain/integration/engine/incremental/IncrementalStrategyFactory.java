package com.datanote.domain.integration.engine.incremental;

/**
 * 增量策略工厂。
 */
public final class IncrementalStrategyFactory {

    private IncrementalStrategyFactory() {
    }

    public static IncrementalStrategy get(String type) {
        if ("AUTO_INCREMENT".equalsIgnoreCase(type)) {
            return new AutoIncrementStrategy();
        }
        return new TimestampStrategy();
    }
}
