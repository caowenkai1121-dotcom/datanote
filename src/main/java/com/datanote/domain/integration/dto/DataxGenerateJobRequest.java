package com.datanote.domain.integration.dto;

import lombok.Data;

/**
 * DataX 生成任务配置请求
 */
@Data
public class DataxGenerateJobRequest {
    private String db;
    private String table;
    private String syncMode;
    private String datasourceId;
}
