package com.datanote.domain.integration.engine;

import com.datanote.domain.integration.dto.SyncContext;

/**
 * 同步引擎统一接口。
 */
public interface SyncEngine {
    void sync(SyncContext ctx);
}
