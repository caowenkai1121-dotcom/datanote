package com.datanote.domain.governance.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务术语 — 对应 dn_glossary_term
 */
@Data
@TableName("dn_glossary_term")
public class DnGlossaryTerm {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String term;
    private String alias;
    private String definition;
    private String category;
    private LocalDateTime createdAt;
}
