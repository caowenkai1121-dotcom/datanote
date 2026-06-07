package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目资源配额（1:1，对应 dn_project_quota，PK=project_id）。 */
@Data
@TableName("dn_project_quota")
public class DnProjectQuota {
    @TableId(value = "project_id", type = IdType.INPUT)
    private Long projectId;
    private Integer concurrentLimit;
    private Integer timeoutDefault;
    private Integer retryDefault;
    private Integer storageQuotaGb;
    private LocalDateTime updatedAt;
}
