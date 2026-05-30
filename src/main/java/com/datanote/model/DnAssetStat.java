package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产采集快照 — 对应 dn_asset_stat
 */
@Data
@TableName("dn_asset_stat")
public class DnAssetStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tableMetaId;
    private String dbName;
    private String tableName;
    private Long sizeBytes;
    private Long rowCount;
    private LocalDateTime lastAccessAt;
    private BigDecimal costEstimate;
    private LocalDateTime collectedAt;
}
