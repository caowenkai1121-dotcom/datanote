-- 数据治理 M11：治理健康分 + 工单闭环 + DCMM 成熟度自评（幂等：CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY）
USE datanote;

-- 治理项规则库：五维打分项（配置表驱动，权重可调）
CREATE TABLE IF NOT EXISTS dn_governance_metric (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  dimension   VARCHAR(20)  NOT NULL COMMENT '规范/质量/安全/生命周期/血缘',
  metric_code VARCHAR(40)  NOT NULL COMMENT '指标编码',
  metric_name VARCHAR(80)  NOT NULL COMMENT '指标名称',
  weight      DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT '权重(同维度内细分;维度总权重由各项汇总)',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  UNIQUE KEY uk_dim_code (dimension, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理项规则库(五维打分项)';

-- 预置默认权重：规范20/质量25/安全25/生命周期15/血缘15（每维度一个兜底项，同维度内可再加项细分）
INSERT INTO dn_governance_metric (dimension, metric_code, metric_name, weight, enabled) VALUES
  ('规范',     'STANDARD_PASS_RATE', '落标率',         20, 1),
  ('质量',     'QUALITY_PASS_RATE',  '质量通过率',     25, 1),
  ('安全',     'SECURITY_COVERAGE',  '分级覆盖率',     25, 1),
  ('生命周期', 'LIFECYCLE_COMPLETE', '资产治理完整度', 15, 1),
  ('血缘',     'LINEAGE_COVERAGE',   '血缘覆盖率',     15, 1)
ON DUPLICATE KEY UPDATE metric_name = VALUES(metric_name), weight = VALUES(weight);

-- 健康分快照（时序）
CREATE TABLE IF NOT EXISTS dn_governance_score (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  score_date  DATE         NOT NULL COMMENT '快照日期',
  total_score DECIMAL(6,2) NOT NULL COMMENT '总分0-100',
  dim_scores  JSON         DEFAULT NULL COMMENT '各维度分 {维度:分}',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_score_date (score_date),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康分快照(时序)';

-- 治理工单单一事实表：问题→工单→整改→复检闭环
CREATE TABLE IF NOT EXISTS dn_governance_issue (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  issue_type  VARCHAR(40)  NOT NULL COMMENT '问题类型(STANDARD/QUALITY/SECURITY/LINEAGE/LIFECYCLE/OTHER)',
  dimension   VARCHAR(20)  DEFAULT NULL COMMENT '所属维度',
  object_ref  VARCHAR(255) DEFAULT NULL COMMENT '对象引用(库.表.列等)',
  title       VARCHAR(200) NOT NULL COMMENT '标题',
  description TEXT         DEFAULT NULL COMMENT '描述',
  severity    VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'HIGH/MEDIUM/LOW',
  owner       VARCHAR(80)  DEFAULT NULL COMMENT '负责人',
  status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FIXING/RESOLVED/VERIFIED/CLOSED',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_status (status),
  INDEX idx_owner (owner),
  INDEX idx_dimension (dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理工单单一事实表';

-- DCMM 八大域成熟度自评
CREATE TABLE IF NOT EXISTS dn_maturity_assessment (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  domain      VARCHAR(40)  NOT NULL COMMENT 'DCMM八大域:数据战略/数据治理/数据架构/数据应用/数据安全/数据质量/数据标准/数据生存周期',
  score       DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '自评分0-100',
  level       INT          NOT NULL DEFAULT 1 COMMENT '成熟度等级1-5',
  note        VARCHAR(500) DEFAULT NULL COMMENT '备注',
  assessed_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_domain (domain),
  INDEX idx_assessed_at (assessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DCMM八大域成熟度自评';
