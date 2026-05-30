-- 数据治理 M3：统一血缘边表（表级 + 字段级）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_lineage_edge (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  level_type     VARCHAR(10)  NOT NULL COMMENT 'TABLE / COLUMN',
  src_db         VARCHAR(100) NOT NULL DEFAULT '',
  src_table      VARCHAR(200) NOT NULL DEFAULT '',
  src_column     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '表级边为空串',
  dst_db         VARCHAR(100) NOT NULL DEFAULT '',
  dst_table      VARCHAR(200) NOT NULL DEFAULT '',
  dst_column     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '表级边为空串',
  transform_type VARCHAR(20)  DEFAULT 'DIRECT' COMMENT 'DIRECT/TRANSFORM/MASK',
  source         VARCHAR(16)  NOT NULL DEFAULT 'MAPPING' COMMENT 'MAPPING/SQL/SCHEDULE/MANUAL',
  confidence     INT          DEFAULT 100 COMMENT '置信度 0-100',
  job_id         BIGINT       DEFAULT NULL COMMENT '来源同步任务ID',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  -- 前缀索引：8 列全长在 utf8mb4 下超 InnoDB 3072 字节上限，按前缀取唯一(库表列名极少超此长度)
  UNIQUE KEY uk_edge (level_type, src_db(64), src_table(128), src_column(128), dst_db(64), dst_table(128), dst_column(128), source),
  INDEX idx_src (src_db, src_table),
  INDEX idx_dst (dst_db, dst_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据血缘边';
