package com.datanote.domain.project.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目-标签关联（dn_project_tag_mapping）。 */
@Data
@TableName("dn_project_tag_mapping")
public class DnProjectTagMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long tagId;
}
