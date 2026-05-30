-- 数据治理 M10：资产盘点 + 生命周期 + 成本 + 无用表识别（幂等：CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY）
USE datanote;

-- 生命周期策略：冷热分层 / TTL / 归档；应用后自动下发 Doris 原生 DDL
CREATE TABLE IF NOT EXISTS dn_lifecycle_policy (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  db_name     VARCHAR(128) NOT NULL COMMENT '库名',
  table_name  VARCHAR(128) NOT NULL COMMENT '表名',
  policy_type VARCHAR(16)  NOT NULL COMMENT 'HOT_COLD/TTL/ARCHIVE',
  cold_days   INT          DEFAULT NULL COMMENT '冷下沉天数(HOT_COLD)',
  ttl_days    INT          DEFAULT NULL COMMENT '保留天数(TTL/dynamic_partition)',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  status      VARCHAR(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW/ACTIVE/PENDING/FAILED/DROP_PENDING/DROPPED',
  ddl_text    TEXT         DEFAULT NULL COMMENT '最近一次下发的DDL',
  last_msg    VARCHAR(500) DEFAULT NULL COMMENT '最近一次结果/异常信息',
  drop_due_at DATETIME     DEFAULT NULL COMMENT '销毁到期时间(软删宽限期)',
  approver    VARCHAR(80)  DEFAULT NULL COMMENT '销毁审批人',
  reason      VARCHAR(255) DEFAULT NULL COMMENT '销毁原因',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_db_table_type (db_name, table_name, policy_type),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生命周期策略';

-- 资产采集快照：体量 / 行数 / 最近访问 / 成本
CREATE TABLE IF NOT EXISTS dn_asset_stat (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id  BIGINT       DEFAULT NULL COMMENT '关联 dn_table_meta.id',
  db_name        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '库名',
  table_name     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '表名',
  size_bytes     BIGINT       DEFAULT NULL COMMENT '体量(字节)',
  row_count      BIGINT       DEFAULT NULL COMMENT '行数',
  last_access_at DATETIME     DEFAULT NULL COMMENT '最近访问时间(兜底用采集时间)',
  cost_estimate  DECIMAL(18,4) DEFAULT NULL COMMENT '估算成本(单价×体量)',
  collected_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
  INDEX idx_table_meta (table_meta_id),
  INDEX idx_db_table (db_name, table_name),
  INDEX idx_collected_at (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产采集快照(体量/访问/成本)';

-- 可配项：写入 dn_system_config（成本单价/销毁宽限期/无用表久未访问阈值）
INSERT INTO dn_system_config (config_key, config_value, description) VALUES
  ('lifecycle.cost.unit_price',   '0.05', '存储单价(元/GB/月)'),
  ('lifecycle.drop.grace_days',   '30',   '销毁软删宽限期(天)'),
  ('lifecycle.unused.access_days','90',   '无用表久未访问阈值(天)')
ON DUPLICATE KEY UPDATE description = VALUES(description);
