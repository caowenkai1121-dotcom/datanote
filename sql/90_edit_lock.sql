-- 并发编辑防护: 独占编辑锁(悲观, 带心跳超时自动释放)。通用: resource_type+resource_id 标识任意资源。
-- 配合保存时乐观版本校验兜底。幂等。
CREATE TABLE IF NOT EXISTS dn_edit_lock (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  resource_type VARCHAR(32)  NOT NULL COMMENT 'SCRIPT/MODEL/QUALITY_RULE/METRIC/STANDARD/MDM/SYNC_JOB/PROJECT...',
  resource_id   VARCHAR(64)  NOT NULL COMMENT '资源主键(字符串)',
  holder        VARCHAR(64)  NOT NULL COMMENT '持锁用户名',
  acquired_at   DATETIME     NOT NULL COMMENT '获锁时间',
  heartbeat_at  DATETIME     NOT NULL COMMENT '最近心跳(超时即视为释放)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_edit_lock (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源独占编辑锁';
