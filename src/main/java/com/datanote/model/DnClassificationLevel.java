package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 分级模型字典 — 对应 dn_classification_level
 */
@Data
@TableName("dn_classification_level")
public class DnClassificationLevel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scheme;     // NATIONAL / FINANCE
    private String levelCode;  // 编码
    private String levelName;  // 名称
    private Integer sort;      // 由低到高排序，数字越大密级越高
}
