package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目收藏/置顶（dn_project_favorite）。 */
@Data
@TableName("dn_project_favorite")
public class DnProjectFavorite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private Long projectId;
    private Integer pinned;
    private LocalDateTime createdAt;
}
