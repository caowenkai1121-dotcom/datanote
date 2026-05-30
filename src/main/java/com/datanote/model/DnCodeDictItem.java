package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 码表明细项实体 — 对应 dn_code_dict_item
 */
@Data
@TableName("dn_code_dict_item")
public class DnCodeDictItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dictId;
    private String itemKey;
    private String itemValue;
    private Integer sort;
}
