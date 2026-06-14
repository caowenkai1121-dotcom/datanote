package com.datanote.domain.develop.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 片段库 — dn_sql_snippet 表。
 * 数据开发常用 SQL 片段沉淀: 保存命名片段 → 编辑器一键插入, 提升复用效率。按创建人隔离。
 */
@Data
@TableName("dn_sql_snippet")
public class DnSqlSnippet {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;          // 片段名(同一用户下唯一)
    private String content;       // SQL 内容
    private String description;   // 说明(可选)
    private String category;      // 分类(可选, 如 ODS/DWD/工具)
    private Integer useCount;     // 插入次数(热度)
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
