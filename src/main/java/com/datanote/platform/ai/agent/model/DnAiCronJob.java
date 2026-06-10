package com.datanote.platform.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI agent 定时自治任务（dn_ai_cron_job）。到点在干净会话跑 agent 提示, 写动作仍走审批。 */
@Data
@TableName("dn_ai_cron_job")
public class DnAiCronJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** Spring 6 段 cron 或 everyNm/everyNh/everyNd */
    private String scheduleCron;
    private String prompt;
    private Integer enabled;
    private Integer silent;
    private String owner;
    private String lastSessionId;
    private LocalDateTime nextRun;
    private LocalDateTime lastRun;
    private String lastStatus;
    private Integer runCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
