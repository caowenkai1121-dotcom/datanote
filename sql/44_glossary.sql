-- 数据治理 F4：业务术语表（Glossary）。幂等：CREATE TABLE IF NOT EXISTS
USE datanote;

CREATE TABLE IF NOT EXISTS dn_glossary_term (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  term        VARCHAR(128) NOT NULL COMMENT '术语名',
  alias       VARCHAR(255) DEFAULT NULL COMMENT '别名(逗号分隔)',
  definition  TEXT         DEFAULT NULL COMMENT '定义/解释',
  category    VARCHAR(64)  DEFAULT NULL COMMENT '分类',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX idx_term (term),
  INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务术语表';
