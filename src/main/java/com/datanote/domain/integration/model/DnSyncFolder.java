package com.datanote.domain.integration.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 关系库同步任务文件夹实体 — 对应 dn_sync_folder 表
 */
@Data
@TableName("dn_sync_folder")
public class DnSyncFolder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String folderName;
    private Long parentId;
    private LocalDateTime createdAt;
}
