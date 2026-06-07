package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目成员（对应 dn_project_member）。 */
@Data
@TableName("dn_project_member")
public class DnProjectMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String username;
    private String projectRole;
    private String addedBy;
    private LocalDateTime createdAt;
}
