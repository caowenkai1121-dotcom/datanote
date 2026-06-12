package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目任务评论（dn_project_task_comment）。IV-1 协作触达第一步。 */
@Data
@TableName("dn_project_task_comment")
public class DnProjectTaskComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String author;
    private String content;
    private LocalDateTime createdAt;
}
