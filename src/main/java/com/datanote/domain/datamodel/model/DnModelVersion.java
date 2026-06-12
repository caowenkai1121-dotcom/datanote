package com.datanote.domain.datamodel.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型版本历史(每次审批发布产生一份快照) — dn_model_version 表。
 */
@Data
@TableName("dn_model_version")
public class DnModelVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private Integer version;
    private String snapshotJson;
    private String changeSummary;
    private String publishedBy;
    private LocalDateTime publishedAt;
}
