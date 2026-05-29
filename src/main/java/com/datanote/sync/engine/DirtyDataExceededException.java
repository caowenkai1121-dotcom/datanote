package com.datanote.sync.engine;

/** 脏数据超阈值,中止该表同步。 */
public class DirtyDataExceededException extends RuntimeException {
    public DirtyDataExceededException(String msg) { super(msg); }
}
