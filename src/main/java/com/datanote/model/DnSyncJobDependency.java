package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步任务依赖（轻量 DAG）— 对应 dn_sync_job_dependency 表。
 */
@Data
@TableName("dn_sync_job_dependency")
public class DnSyncJobDependency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long syncJobId;          // 下游任务
    private Long upstreamSyncJobId;  // 上游任务
    private Integer dependsAll;      // 1=全部上游 SUCCESS 才触发
    private LocalDateTime createTime;
}
