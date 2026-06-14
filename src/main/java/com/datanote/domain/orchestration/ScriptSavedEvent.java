package com.datanote.domain.orchestration;

/**
 * 脚本变更事件 — 数据开发脚本保存成功后由 develop 侧发布，
 * orchestration 侧监听并异步重建 SQL 血缘边（与同步任务的 {@link SyncJobChangedEvent} 同款解耦机制，
 * 避免开发改完 SQL 后血缘要等夜间 02:40 兜底才更新）。
 */
public class ScriptSavedEvent {

    private final Long scriptId;

    public ScriptSavedEvent(Long scriptId) {
        this.scriptId = scriptId;
    }

    public Long getScriptId() {
        return scriptId;
    }
}
