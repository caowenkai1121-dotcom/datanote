-- 数据治理 M2：元数据采集扩列 + 采集日志（幂等，按 information_schema 守护）
USE datanote;

-- dn_table_meta 扩列（仅 db_type 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_table_meta' AND COLUMN_NAME = 'db_type');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_table_meta
     ADD COLUMN db_type VARCHAR(20) DEFAULT NULL COMMENT ''来源类型 MYSQL/DORIS'',
     ADD COLUMN table_type VARCHAR(30) DEFAULT NULL COMMENT ''表类型 BASE TABLE/VIEW'',
     ADD COLUMN size_bytes BIGINT DEFAULT NULL COMMENT ''数据体量(字节)'',
     ADD COLUMN last_collected_at DATETIME DEFAULT NULL COMMENT ''最近采集时间''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- dn_column_meta 扩列（仅 data_type 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_column_meta' AND COLUMN_NAME = 'data_type');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_column_meta
     ADD COLUMN data_type VARCHAR(120) DEFAULT NULL COMMENT ''物理类型(如 varchar(50))'',
     ADD COLUMN column_key VARCHAR(16) DEFAULT NULL COMMENT ''键标识 PRI/MUL/UNI'',
     ADD COLUMN is_nullable VARCHAR(8) DEFAULT NULL COMMENT ''是否可空 YES/NO'',
     ADD COLUMN ordinal INT DEFAULT NULL COMMENT ''字段序号'',
     ADD COLUMN last_collected_at DATETIME DEFAULT NULL COMMENT ''最近采集时间''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 采集日志
CREATE TABLE IF NOT EXISTS dn_meta_collect_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  datasource_id BIGINT DEFAULT NULL COMMENT '数据源ID(0=Doris数仓)',
  db_type       VARCHAR(20) DEFAULT NULL COMMENT 'MYSQL/DORIS',
  scope         VARCHAR(200) DEFAULT NULL COMMENT '采集范围(all 或 库名)',
  table_count   INT DEFAULT 0,
  column_count  INT DEFAULT 0,
  status        VARCHAR(20) DEFAULT NULL COMMENT 'success/error',
  message       TEXT,
  duration_ms   BIGINT DEFAULT 0,
  started_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  finished_at   DATETIME DEFAULT NULL,
  INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据采集日志';
