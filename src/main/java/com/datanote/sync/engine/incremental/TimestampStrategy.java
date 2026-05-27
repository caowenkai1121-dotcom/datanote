package com.datanote.sync.engine.incremental;

/**
 * 时间戳增量策略：按字符串字典序比较（标准时间格式 yyyy-MM-dd HH:mm:ss 字典序与时间序一致）。
 */
public class TimestampStrategy implements IncrementalStrategy {

    @Override
    public String type() {
        return "TIMESTAMP";
    }

    @Override
    public int compare(Object a, Object b) {
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    @Override
    public String toStored(Object value) {
        return String.valueOf(value);
    }
}
