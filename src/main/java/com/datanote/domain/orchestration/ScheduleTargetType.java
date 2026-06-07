package com.datanote.domain.orchestration;

import com.datanote.domain.develop.model.DnScript;
import com.datanote.domain.integration.model.DnSyncTask;
/**
 * 调度目标类型 —— 统一脚本与同步任务两类在上下线编排中的差异分派。
 * SCRIPT=数据开发脚本(DnScript)，SYNC=DataX 同步任务(DnSyncTask)。
 */
public enum ScheduleTargetType {
    SCRIPT,
    SYNC
}
