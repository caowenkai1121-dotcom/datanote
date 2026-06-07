package com.datanote.domain.integration.dto;

import lombok.Data;

/**
 * Hive SQL 执行请求
 */
@Data
public class HiveExecuteRequest {
    private String sql;
}
