package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目公告（dn_project_announcement）。 */
@Data
@TableName("dn_project_announcement")
public class DnProjectAnnouncement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String title;
    private String content;
    private String priority;
    private LocalDateTime expireAt;
    private String createdBy;
    private LocalDateTime createdAt;
}
