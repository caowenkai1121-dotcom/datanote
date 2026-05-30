package com.datanote.service;

import com.datanote.util.DorisSqlUtil;

import java.time.LocalDateTime;

/**
 * 生命周期纯函数集 —— 无用表四要素打分、成本估算、销毁宽限期、Doris DDL 构造。
 * 全部无状态、无 Spring 依赖，便于单测。
 */
public final class LifecycleScorer {

    private LifecycleScorer() {
    }

    /** 久未访问判候选的最小天数（与 dn_system_config lifecycle.unused.access_days 同义，默认值）。 */
    public static final int DEFAULT_IDLE_DAYS = 90;
    /** 判候选的最低综合分。 */
    public static final int CANDIDATE_SCORE = 50;
    private static final String NAME_PATTERN = "[a-zA-Z0-9_]+";

    /** 无用表打分结果。 */
    public static final class UnusedScore {
        public final int score;       // 0-100 综合分
        public final boolean candidate;

        public UnusedScore(int score, boolean candidate) {
            this.score = score;
            this.candidate = candidate;
        }
    }

    /**
     * 无用表四要素打分：久未访问 + 体量 + 无下游血缘 + 无任务引用。
     *
     * @param lastAccessDays        距最近访问天数（越大越久未用）
     * @param sizeBytes             体量字节
     * @param hasDownstreamLineage  是否存在下游血缘（销毁第一道护栏的判定面：有则永不候选）
     * @param hasTaskRef            是否被任务引用（有则永不候选）
     */
    public static UnusedScore scoreUnusedTable(long lastAccessDays, long sizeBytes,
                                               boolean hasDownstreamLineage, boolean hasTaskRef) {
        // 久未访问：90 天满 40 分，1 年封顶
        double idle = clamp(lastAccessDays / 90.0, 0, 1) * 40;
        // 体量：10GB 满 30 分
        double tenGb = 10.0 * 1024 * 1024 * 1024;
        double size = clamp((sizeBytes <= 0 ? 0 : sizeBytes) / tenGb, 0, 1) * 30;
        // 无下游血缘 +20、无任务引用 +10
        double noLineage = hasDownstreamLineage ? 0 : 20;
        double noTask = hasTaskRef ? 0 : 10;
        int score = (int) Math.round(idle + size + noLineage + noTask);

        boolean candidate = !hasDownstreamLineage
                && !hasTaskRef
                && lastAccessDays >= DEFAULT_IDLE_DAYS
                && score >= CANDIDATE_SCORE;
        return new UnusedScore(score, candidate);
    }

    /** 成本估算：体量(GB) × 单价(元/GB/月)。负数/0 体量按 0。 */
    public static double estimateCost(long sizeBytes, double unitPricePerGbMonth) {
        if (sizeBytes <= 0 || unitPricePerGbMonth <= 0) return 0.0;
        double gb = sizeBytes / (1024.0 * 1024 * 1024);
        return gb * unitPricePerGbMonth;
    }

    /** 销毁宽限期到期时间 = 标记时间 + 宽限天数。 */
    public static LocalDateTime dropDueAt(LocalDateTime markedAt, int graceDays) {
        return markedAt.plusDays(graceDays);
    }

    /**
     * 构造应用生命周期策略时下发的 Doris 原生 DDL。
     * - TTL：dynamic_partition 仅保留近 ttlDays 天（按天分区）。
     * - HOT_COLD：storage_policy 做冷热分层（依赖 Doris 已配对象存储冷后端）。
     * - ARCHIVE：等同 TTL 缩短保留（演示用，归档/销毁仍走护栏流程）。
     * 库表名经白名单校验，非法直接抛 IllegalArgumentException 防注入。
     */
    public static String buildDorisDdl(String db, String table, String policyType,
                                       Integer coldDays, Integer ttlDays) {
        if (db == null || !db.matches(NAME_PATTERN) || table == null || !table.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("非法的库名或表名");
        }
        String qualified = DorisSqlUtil.quoteQualified(db, table);
        String type = policyType == null ? "" : policyType.trim().toUpperCase();
        switch (type) {
            case "TTL":
            case "ARCHIVE": {
                int keep = ttlDays != null ? ttlDays : 30;
                // dynamic_partition 仅保留近 keep 天（start=-keep），并自动建未来分区
                return "ALTER TABLE " + qualified + " SET (\n"
                        + "  \"dynamic_partition.enable\" = \"true\",\n"
                        + "  \"dynamic_partition.time_unit\" = \"DAY\",\n"
                        + "  \"dynamic_partition.start\" = \"-" + keep + "\",\n"
                        + "  \"dynamic_partition.end\" = \"3\",\n"
                        + "  \"dynamic_partition.prefix\" = \"p\"\n"
                        + ")";
            }
            case "HOT_COLD": {
                int cold = coldDays != null ? coldDays : 7;
                // 冷热分层：热数据 cold 天后下沉冷后端，需 Doris 已配 storage policy 对应资源
                return "ALTER TABLE " + qualified + " SET (\n"
                        + "  \"storage_policy\" = \"dn_cold_policy_" + cold + "d\"\n"
                        + ")";
            }
            default:
                throw new IllegalArgumentException("未知策略类型: " + policyType);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
