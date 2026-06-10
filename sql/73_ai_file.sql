-- AI 数据中心文件(用户上传/下载; agent 生成文件复用)
CREATE TABLE IF NOT EXISTS dn_ai_file (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_name VARCHAR(500) NOT NULL COMMENT '原始文件名(显示用)',
  stored_name VARCHAR(200) NOT NULL COMMENT '磁盘存储名(uuid.ext, 防路径穿越)',
  content_type VARCHAR(160) DEFAULT NULL,
  size_bytes BIGINT DEFAULT 0,
  owner VARCHAR(100) DEFAULT NULL COMMENT '归属用户',
  source VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT 'user上传 / agent生成',
  session_id VARCHAR(64) DEFAULT NULL COMMENT '关联会话(可空)',
  created_at DATETIME DEFAULT NULL,
  KEY idx_owner (owner),
  KEY idx_stored (stored_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 数据中心文件';
