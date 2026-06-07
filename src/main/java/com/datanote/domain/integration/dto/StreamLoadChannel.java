package com.datanote.domain.integration.dto;

import java.util.List;

/**
 * DS-M4：Stream Load 写入通道回调。BatchWriter.flush 优先走此通道，
 * 失败回退 JDBC。默认 null（不启用），执行器按 writeChannel 配置装配。
 */
@FunctionalInterface
public interface StreamLoadChannel {
    /** 导入一批行，返回写入行数；失败抛异常（调用方回退 JDBC）。 */
    long load(String targetTable, List<String> columns, List<Object[]> rows) throws Exception;
}
