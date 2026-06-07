package com.datanote.domain.governance.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 健康分快照（时序） — 对应 dn_governance_score
 */
@Data
@TableName("dn_governance_score")
public class DnGovernanceScore {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate scoreDate;
    private BigDecimal totalScore;
    private String dimScores;   // JSON: {维度:分}
    private LocalDateTime createdAt;
}
