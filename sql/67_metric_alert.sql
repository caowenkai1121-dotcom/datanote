-- ============================================================
-- R12 指标预警 — 指标值阈值规则。指标计算后按规则判定，越界自动生成治理工单(闭合 消费→治理 环)。
-- 幂等 CREATE TABLE IF NOT EXISTS。
-- ============================================================
CREATE TABLE IF NOT EXISTS dn_metric_alert_rule (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  metric_id     BIGINT       NOT NULL COMMENT '指标ID(dn_metric)',
  metric_code   VARCHAR(100) DEFAULT NULL,
  op            VARCHAR(8)   NOT NULL COMMENT '比较符: GT/LT/GE/LE/NE/OUT(区间外)/IN(区间内)',
  threshold_min DECIMAL(24,4) DEFAULT NULL COMMENT '阈值/区间下界',
  threshold_max DECIMAL(24,4) DEFAULT NULL COMMENT '区间上界(OUT/IN 用)',
  severity      VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'HIGH/MEDIUM/LOW',
  enabled       TINYINT      NOT NULL DEFAULT 1,
  remark        VARCHAR(255) DEFAULT NULL,
  created_by    VARCHAR(80)  DEFAULT NULL,
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_mar_metric (metric_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标预警阈值规则(消费层)';
