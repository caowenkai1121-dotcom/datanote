-- ============================================================
-- R10 数据消费层（domain.consumption）— 填补断点④"治理成果无消费出口"
-- 指标值时序快照 + 数据消费审计流水。幂等 CREATE TABLE IF NOT EXISTS，安全可重跑。
-- ============================================================

-- 指标值时序快照：指标执行引擎按 dn_metric.calc_formula 计算后落库，供查询/看板/导出消费
CREATE TABLE IF NOT EXISTS dn_metric_value (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  metric_id    BIGINT       NOT NULL COMMENT '指标ID(dn_metric)',
  metric_code  VARCHAR(100) DEFAULT NULL COMMENT '指标编码(冗余便于查询)',
  metric_value DECIMAL(24,4) DEFAULT NULL COMMENT '指标数值',
  value_text   VARCHAR(255) DEFAULT NULL COMMENT '非数值结果兜底',
  biz_date     DATE         DEFAULT NULL COMMENT '业务日期(空=即时快照)',
  dims         VARCHAR(500) DEFAULT NULL COMMENT '维度快照',
  run_status   VARCHAR(16)  NOT NULL DEFAULT 'success' COMMENT 'success/error',
  error_msg    VARCHAR(500) DEFAULT NULL,
  duration_ms  BIGINT       DEFAULT NULL,
  calc_sql     TEXT         DEFAULT NULL COMMENT '本次计算所用SQL',
  created_by   VARCHAR(80)  DEFAULT NULL,
  created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_mv_metric (metric_id),
  INDEX idx_mv_code (metric_code),
  INDEX idx_mv_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标值时序快照(消费层)';

-- 数据消费审计流水：透明记录"谁/何时/消费了什么/行数/耗时/成败"，支撑指标-消费方分析、僵尸指标识别
CREATE TABLE IF NOT EXISTS dn_consumption_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  consumer    VARCHAR(120) DEFAULT NULL COMMENT '消费方/用户',
  target_type VARCHAR(32)  DEFAULT NULL COMMENT 'METRIC_VALUE/METRIC_HISTORY/EXPORT/CALC',
  target_code VARCHAR(100) DEFAULT NULL COMMENT '指标code等目标标识',
  action      VARCHAR(32)  DEFAULT NULL COMMENT 'QUERY/EXPORT/CALC',
  row_count   BIGINT       DEFAULT NULL,
  duration_ms BIGINT       DEFAULT NULL,
  success     TINYINT      DEFAULT 1,
  detail      VARCHAR(500) DEFAULT NULL,
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_cl_target (target_code),
  INDEX idx_cl_consumer (consumer),
  INDEX idx_cl_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据消费审计流水(消费层)';
