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
        // null 视为最小，避免 String.valueOf(null)="null" 污染水位字典序
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    @Override
    public String toStored(Object value) {
        return String.valueOf(value);
    }
}
