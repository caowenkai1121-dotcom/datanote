package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("dn_backfill_instance")
public class DnBackfillInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long backfillId;
    private LocalDate runDate;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer duration;
    private String log;
}
