package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目模板（dn_project_template）。 */
@Data
@TableName("dn_project_template")
public class DnProjectTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateName;
    private String templateType;
    private String description;
    private String configJson;
    private String createdBy;
    private LocalDateTime createdAt;
}
