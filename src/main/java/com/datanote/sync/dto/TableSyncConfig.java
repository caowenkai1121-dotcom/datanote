package com.datanote.sync.dto;

import lombok.Data;

/**
 * 单表同步配置（dn_sync_job.table_config JSON 数组的元素）。
 */
@Data
public class TableSyncConfig {
    private String sourceTable;
    private String targetTable;
    private Boolean createTargetTable = Boolean.FALSE;
    private String incrementalField;   // 计划2用
    private String incrementalType;    // 计划2用：TIMESTAMP/AUTO_INCREMENT
    private String incrementalValue;   // 计划2用：断点
}
