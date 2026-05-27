package com.datanote.sync.engine;

/**
 * 按 syncMode 选择同步引擎。
 */
public final class SyncEngineFactory {

    private SyncEngineFactory() {
    }

    public static SyncEngine get(String syncMode) {
        if ("INCREMENTAL".equalsIgnoreCase(syncMode)) {
            return new IncrementalSyncEngine();
        }
        return new FullSyncEngine();
    }
}
