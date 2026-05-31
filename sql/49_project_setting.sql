-- 项目管理 PM-E3：资源配额 + 环境参数映射（轻量配置，幂等）
USE datanote;

-- 资源配额（1:1 项目）
CREATE TABLE IF NOT EXISTS dn_project_quota (
  project_id       BIGINT PRIMARY KEY,
  concurrent_limit INT DEFAULT 4 COMMENT '并发上限',
  timeout_default  INT DEFAULT 0 COMMENT '默认超时秒(0不限)',
  retry_default    INT DEFAULT 0 COMMENT '默认重试次',
  storage_quota_gb INT DEFAULT 0 COMMENT '存储配额GB(0不限)',
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目资源配额';

-- 环境参数映射（参数键 → 开发/生产 值；配置存档，供发布参考）
CREATE TABLE IF NOT EXISTS dn_project_env_param (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT NOT NULL,
  param_key   VARCHAR(128) NOT NULL,
  dev_value   VARCHAR(512) DEFAULT NULL,
  prod_value  VARCHAR(512) DEFAULT NULL,
  remark      VARCHAR(256) DEFAULT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_proj_param (project_id, param_key),
  INDEX idx_pep_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目环境参数映射';
