package com.datanote.platform.config.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 码表实体 — 对应 dn_code_dict
 */
@Data
@TableName("dn_code_dict")
public class DnCodeDict {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dictCode;
    private String dictName;
    private String description;
}
