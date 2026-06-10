package com.datanote.domain.mdm.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 黄金记录变更历史快照 — 对应 dn_mdm_golden_history 表。
 * 每次 save/publish/deactivate/merge 写一条变更后快照，支撑版本回溯与 diff。
 */
@Data
@TableName("dn_mdm_golden_history")
public class DnMdmGoldenHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long goldenId;
    private Long entityId;
    private Integer version;
    private String bizKey;
    private String status;
    private String dataJson;
    private String changeType;   // create/update/publish/deactivate/merge
    private LocalDateTime createdAt;
}
