package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 业务流程SOP 版本历史 — dn_ai_industry_sop_hist。对话式纠正/编辑/合并留痕, 支持回滚审计。 */
@Data
@TableName("dn_ai_industry_sop_hist")
public class DnAiIndustrySopHist {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sopId;
    private Integer version;
    private String title;
    private String content;
    private String op;        // create/edit/correct/merge/archive
    private String editor;
    private LocalDateTime snapshotAt;
}
