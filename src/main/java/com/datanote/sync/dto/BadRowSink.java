package com.datanote.sync.dto;

/**
 * DS-M1：坏行落 DLQ 回调。BatchWriter 逐行回退定位到坏行时调用，由执行器落 dn_sync_error_row。
 * 默认空实现（无副作用），执行器按需装配。
 */
@FunctionalInterface
public interface BadRowSink {
    void accept(String sourceTable, Object[] writeRow, String errorMsg);
}
