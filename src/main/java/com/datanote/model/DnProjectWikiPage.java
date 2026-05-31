package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目文档 Wiki 页面（dn_project_wiki_page）。 */
@Data
@TableName("dn_project_wiki_page")
public class DnProjectWikiPage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long parentId;
    private String title;
    private String content;
    private Integer sortOrder;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
