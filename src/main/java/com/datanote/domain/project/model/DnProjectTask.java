package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 项目任务（dn_project_task）。 */
@Data
@TableName("dn_project_task")
public class DnProjectTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long milestoneId;
    private String title;
    private String description;
    private String assignee;
    private String priority;
    private String status;
    private LocalDate dueDate;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
