package com.datanote.util;

/** 系统监控指标的纯计算工具（占用百分比、告警级别），便于单测。 */
public final class SysMetricsUtil {

    private SysMetricsUtil() {
    }

    /** 占用百分比 0-100；total<=0 返回 0；越界做钳制。 */
    public static int usagePct(long used, long total) {
        if (total <= 0) return 0;
        long p = Math.round(used * 100.0 / total);
        if (p < 0) return 0;
        if (p > 100) return 100;
        return (int) p;
    }

    /** 占用型告警级别：>=90 危险(danger)，>=75 警告(warn)，否则正常(ok)。 */
    public static String usageLevel(int pct) {
        if (pct >= 90) return "danger";
        if (pct >= 75) return "warn";
        return "ok";
    }

    /** 毫秒时长转可读（如 1天2小时 / 3小时5分 / 12分 / 30秒）。 */
    public static String humanDuration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, m = s / 60, h = m / 60, d = h / 24;
        if (d > 0) return d + "天" + (h % 24) + "小时";
        if (h > 0) return h + "小时" + (m % 60) + "分";
        if (m > 0) return m + "分";
        return s + "秒";
    }
}
