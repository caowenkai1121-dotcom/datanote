-- 指标-资产关联表（指标关联到具体库.表.列）
CREATE TABLE IF NOT EXISTS `dn_metric_ref` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `metric_id` BIGINT NOT NULL COMMENT '指标ID(dn_metric.id)',
  `db_name` VARCHAR(128) DEFAULT NULL COMMENT '库名',
  `table_name` VARCHAR(128) DEFAULT NULL COMMENT '表名',
  `column_name` VARCHAR(128) DEFAULT NULL COMMENT '列名(可空,表级关联时为空)',
  `ref_type` VARCHAR(32) DEFAULT 'SOURCE' COMMENT '关联类型: SOURCE来源/DIM维度/RESULT结果',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_metric (`metric_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标-资产关联';
