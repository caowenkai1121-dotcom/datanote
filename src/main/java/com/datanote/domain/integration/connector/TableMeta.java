package com.datanote.domain.integration.connector;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 表元数据：列名（按序）+ 主键列。
 */
@Data
public class TableMeta {
    private List<String> columns = new ArrayList<>();
    private List<String> primaryKeys = new ArrayList<>();
}
