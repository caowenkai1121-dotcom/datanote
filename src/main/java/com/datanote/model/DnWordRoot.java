package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 命名词根实体 — 对应 dn_word_root
 */
@Data
@TableName("dn_word_root")
public class DnWordRoot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String wordCn;
    private String wordEn;
    private String abbr;
    private String category;
}
