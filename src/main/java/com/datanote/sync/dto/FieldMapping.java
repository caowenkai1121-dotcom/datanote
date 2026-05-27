package com.datanote.sync.dto;

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
}
