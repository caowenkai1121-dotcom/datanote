package com.datanote.sync.dto;

import com.datanote.sync.connector.DbConnector;
import lombok.Data;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 一次同步运行的上下文与计数器。
 */
@Data
public class SyncContext {
    private Long jobId;
    private Long executionId;
    private DbConnector source;
    private DbConnector target;
    private String sourceDb;
    private String targetDb;
    private List<TableSyncConfig> tables;
    private String writeMode;
    private int batchSize = 1000;

    /** M1：任务级前置/后置 SQL（表级未配置时回退此值）。 */
    private String globalPreSql;
    private String globalPostSql;

    /** 迭代V3：是否在目标表每行额外写入同步时间戳列（来自 job.markSyncTs，1=是）。 */
    private Integer markSyncTs;
    /** 迭代V3：同步时间戳列名（markSyncTs 生效时使用）。 */
    private String syncTsField;

    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    /** M2a：脏数据阈值(null=不限)。 */
    private Integer errorLimitRows;
    private Double errorLimitRatio;
    /** M2a：坏行累计。 */
    private final java.util.concurrent.atomic.AtomicLong dirtyCount = new java.util.concurrent.atomic.AtomicLong(0);
    /** M2a：限速器(null=不限速)。 */
    private com.datanote.sync.util.RateLimiter rateLimiter;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    /** 停止原因：null=未停止 / "manual"=手动停止 / "timeout"=超时。决定最终状态(STOPPED vs FAILED)。 */
    private volatile String stopReason;

    /** 日志回调：(level, message)，由执行层接入 WebSocket。 */
    private BiConsumer<String, String> logCallback = (level, msg) -> {};
    /** 单表增量断点持久化回调：某表成功后立即回写其断点，避免后续表失败丢已成功表的断点。 */
    private Consumer<TableSyncConfig> checkpointCallback = tc -> {};

    /** M2b：全量 chunk 续传回调。 */
    private java.util.function.Function<String,String> chunkLoad = t -> null;
    private java.util.function.BiConsumer<String,String> chunkSave = (t,v) -> {};
    private java.util.function.Consumer<String> chunkClear = t -> {};
    // DS-M1：坏行落 DLQ 回调（默认空实现，执行器装配）
    private BadRowSink badRowSink = (table, row, err) -> {};
    // DS-M4：Stream Load 写入通道（null=不启用,默认JDBC；执行器按 writeChannel 装配）
    private StreamLoadChannel streamLoadChannel = null;

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** 取表级 preSql，空则回退任务级；都空返回 null。 */
    public String getPreSql(TableSyncConfig tc) {
        String t = tc == null ? null : tc.getPreSql();
        if (!blank(t)) return t;
        return blank(globalPreSql) ? null : globalPreSql;
    }
    public String getPostSql(TableSyncConfig tc) {
        String t = tc == null ? null : tc.getPostSql();
        if (!blank(t)) return t;
        return blank(globalPostSql) ? null : globalPostSql;
    }

    public void log(String level, String msg) {
        logCallback.accept(level, msg);
    }

    /** 请求停止：置停止标志并记录原因（manual/timeout），引擎在分页边界检查后中断。 */
    public void requestStop(String reason) {
        this.stopReason = reason;
        this.stopped.set(true);
    }

    /** 单表成功后回写断点。 */
    public void checkpoint(TableSyncConfig tc) {
        checkpointCallback.accept(tc);
    }
}
