package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目环境参数映射（对应 dn_project_env_param）。 */
@Data
@TableName("dn_project_env_param")
public class DnProjectEnvParam {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String paramKey;
    private String devValue;
    private String prodValue;
    private String remark;
    private LocalDateTime createdAt;
}
