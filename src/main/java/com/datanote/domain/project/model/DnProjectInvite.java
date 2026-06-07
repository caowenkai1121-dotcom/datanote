package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目成员邀请（dn_project_invite）。 */
@Data
@TableName("dn_project_invite")
public class DnProjectInvite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String invitee;
    private String projectRole;
    private String token;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
    private String handledBy;
}
