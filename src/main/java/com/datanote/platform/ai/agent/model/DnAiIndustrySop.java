package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 业务流程SOP/行业经验(按业务域) — dn_ai_industry_sop。 */
@Data
@TableName("dn_ai_industry_sop")
public class DnAiIndustrySop {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String domain;       // 业务域(销售/库存/财务/会员; global=通用)
    private String sopType;      // flow/report/caliber/pitfall/glossary
    private String title;
    private String content;
    private String triggerHint;  // 触发词/适用场景(召回用)
    private String source;       // harvest/learned/taught
    private String status;       // active/draft/archived
    private Integer version;
    private Integer hitCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
