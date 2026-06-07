-- ============================================================
-- R13 数据集（数据产品）— 把一段精选 SQL 注册为可复用、受治理(脱敏+审计)的查询/数据产品。
-- 消费层 domain.consumption。幂等 CREATE TABLE IF NOT EXISTS。
-- ============================================================
CREATE TABLE IF NOT EXISTS dn_dataset (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  dataset_code VARCHAR(100) NOT NULL COMMENT '数据集编码(唯一)',
  dataset_name VARCHAR(200) NOT NULL COMMENT '数据集名称',
  description  TEXT         DEFAULT NULL COMMENT '用途/口径',
  default_db   VARCHAR(100) DEFAULT NULL COMMENT '默认库(脱敏改写定位)',
  query_sql    TEXT         NOT NULL COMMENT '精选查询SQL(只读SELECT)',
  owner        VARCHAR(80)  DEFAULT NULL,
  status       INT          NOT NULL DEFAULT 1 COMMENT '1启用/0下线',
  tags         VARCHAR(500) DEFAULT NULL,
  created_by   VARCHAR(80)  DEFAULT NULL,
  created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dataset_code (dataset_code),
  INDEX idx_ds_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据集/数据产品(消费层)';
