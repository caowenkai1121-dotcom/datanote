package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目最近访问（dn_project_access）。 */
@Data
@TableName("dn_project_access")
public class DnProjectAccess {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private Long projectId;
    private LocalDateTime accessAt;
}
