package com.datanote.domain.governance.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据元实体 — 对应 dn_data_element
 */
@Data
@TableName("dn_data_element")
public class DnDataElement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String elementCode;
    private String nameCn;
    private String dataType;
    private Integer length;
    private String valueDomain;
    private String sensitiveType;
    private String securityLevel;
    private String description;
    private LocalDateTime createdAt;
}
