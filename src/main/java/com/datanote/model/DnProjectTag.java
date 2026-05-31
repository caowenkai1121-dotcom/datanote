package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目标签（dn_project_tag）。 */
@Data
@TableName("dn_project_tag")
public class DnProjectTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tagName;
    private String tagColor;
    private String createdBy;
    private LocalDateTime createdAt;
}
