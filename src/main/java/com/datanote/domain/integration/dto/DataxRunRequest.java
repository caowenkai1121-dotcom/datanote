package com.datanote.domain.integration.dto;

import lombok.Data;

/**
 * DataX 执行同步任务请求
 */
@Data
public class DataxRunRequest {
    private String jobId;
    private String jobPath;
}
