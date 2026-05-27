package com.datanote.sync.engine.incremental;

/**
 * 增量策略：决定增量值如何比较与持久化。增量查询用 PreparedStatement 参数化，无需拼值。
 */
public interface IncrementalStrategy {

    String type();

    /** 比较两个增量值（用于求本批最大断点）。a>b 返回正，相等 0，a<b 负。 */
    int compare(Object a, Object b);

    /** 把增量值转为持久化字符串（写回 table_config.incrementalValue）。 */
    String toStored(Object value);
}
