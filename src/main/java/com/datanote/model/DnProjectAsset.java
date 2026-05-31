package com.datanote.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 项目-资产关联（对应 dn_project_asset）。 */
@Data
@TableName("dn_project_asset")
public class DnProjectAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String assetType;
    private Long assetId;
    private String assetName;
    private String createdBy;
    private LocalDateTime createdAt;
}
