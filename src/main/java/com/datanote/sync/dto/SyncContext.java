package com.datanote.sync.dto;

import com.datanote.sync.connector.DbConnector;
import lombok.Data;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

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

    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 日志回调：(level, message)，由执行层接入 WebSocket。 */
    private BiConsumer<String, String> logCallback = (level, msg) -> {};

    public void log(String level, String msg) {
        logCallback.accept(level, msg);
    }
}
