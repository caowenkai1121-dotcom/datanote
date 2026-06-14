package com.datanote.domain.orchestration.dto;

import lombok.Data;

/**
 * 调度配置请求 DTO
 */
@Data
public class ScheduleConfigRequest {
    private String scheduleCron;
    private Integer timeoutSeconds;
    private Integer retryTimes;
    private Integer retryInterval;
    private String warningType;
    private String alertChannel;   // 告警渠道(bell/email等), 前端已配, 此前未落库
    private String alertContact;   // 告警联系人(覆盖默认按创建人通知)
}
