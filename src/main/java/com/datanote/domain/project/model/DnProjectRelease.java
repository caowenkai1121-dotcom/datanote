package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目发布版本（对应 dn_project_release）。 */
@Data
@TableName("dn_project_release")
public class DnProjectRelease {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Integer versionNo;
    private String title;
    private String content;
    private String targetEnv;
    private String status;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String approver;
    private LocalDateTime approvedAt;
    private String approveComment;
    private LocalDateTime releasedAt;
    private LocalDateTime createdAt;
}
