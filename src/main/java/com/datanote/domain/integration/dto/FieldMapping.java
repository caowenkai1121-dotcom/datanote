package com.datanote.domain.integration.dto;

import lombok.Data;

/**
 * 单字段映射（TableSyncConfig.fields 元素）。
 * source：源列名；target：目标列名；sync：是否同步该字段。
 */
@Data
public class FieldMapping {
    private String source;
    private String target;
    private Boolean sync;
    /** M1：空值处理 PASSTHROUGH(默认)/REPLACE_WITH_DEFAULT/SKIP_ROW。 */
    private String nullHandling;
    /** M1：nullHandling=REPLACE_WITH_DEFAULT 时的替代值。 */
    private String defaultValue;
    /** M1：转换函数 JSON，如 {"type":"substring","args":{"start":0,"length":10}}。 */
    private String transformExpression;
    /** M1：脱敏类型 PHONE/EMAIL/IDCARD/HASH_SHA256/REDACT，null=不脱敏。 */
    private String maskingType;
    /** M1：HASH_SHA256 加盐。 */
    private String maskingSalt;
}
