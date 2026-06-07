package com.datanote.sync.dto;

import com.datanote.domain.integration.model.DnSyncJob;
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
    /** 字段映射：null/空=同步全部列（向后兼容）；非空=只同步 sync==true 的字段，源列名 source、目标列名 target。 */
    private java.util.List<FieldMapping> fields;
    /** M1：行过滤 WHERE 条件 JSON，null/空=不过滤。 */
    private String filterExpression;
    /** M1：表级前置SQL，覆盖任务级 DnSyncJob.preSql（可选）。 */
    private String preSql;
    /** M1：表级后置SQL，覆盖任务级 DnSyncJob.postSql（可选）。 */
    private String postSql;
}
