package com.datanote.domain.orchestration;

/**
 * 同步任务变更事件 — 同步任务保存/删除成功后由 integration 侧发布，
 * orchestration 侧监听并重建血缘边（事件解耦，避免 integration→orchestration 直接注入）。
 */
public class SyncJobChangedEvent {

    private final Long jobId;

    public SyncJobChangedEvent(Long jobId) {
        this.jobId = jobId;
    }

    public Long getJobId() {
        return jobId;
    }
}
