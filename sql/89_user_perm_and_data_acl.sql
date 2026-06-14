-- 权限细化: ①用户直授权限点(绕过角色, 叠加生效) ②数据权限(资源级访问控制, 默认公开/黑名单)
-- 幂等(CREATE IF NOT EXISTS)。

-- 用户直授权限点: 有效权限 = 角色并集 ∪ 用户直授
CREATE TABLE IF NOT EXISTS dn_user_perm (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  perm_code  VARCHAR(64) NOT NULL COMMENT '权限点, 与 PermCatalog 对齐',
  created_by VARCHAR(64) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_perm (user_id, perm_code),
  KEY idx_user_perm_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户直授权限点';

-- 数据权限授权: 某资源授给某主体(角色/用户)。某资源有 >=1 行即"受限", 仅被授权主体+owner+超管+data:all 可访问;
-- 无任何行 = 公开(默认)。resource_id 为字符串键(库表用 db.table, 其余用 id 字符串), 兼容各资源。
CREATE TABLE IF NOT EXISTS dn_data_grant (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  resource_type  VARCHAR(32)  NOT NULL COMMENT 'TABLE/PROJECT/MODEL/METRIC/SCRIPT',
  resource_id    VARCHAR(256) NOT NULL COMMENT '资源键: 库表=db.table, 其余=id',
  principal_type VARCHAR(8)   NOT NULL COMMENT 'ROLE/USER',
  principal      VARCHAR(64)  NOT NULL COMMENT '角色编码 或 用户名',
  created_by     VARCHAR(64)  NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_data_grant (resource_type, resource_id, principal_type, principal),
  KEY idx_data_grant_res (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据权限授权(资源级访问控制)';
