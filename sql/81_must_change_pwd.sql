-- 首登强制改密: 管理员建号/重置密码后, 用户首次登录必须改掉初始密码(管理员知道临时口令是安全洞)
-- 幂等: 先查 information_schema, 列不存在才 ADD(兼容 MySQL 8, 其不支持 ADD COLUMN IF NOT EXISTS)
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_user' AND COLUMN_NAME = 'must_change_pwd');
SET @ddl := IF(@col = 0,
  'ALTER TABLE dn_user ADD COLUMN must_change_pwd TINYINT NOT NULL DEFAULT 0 COMMENT ''1需首登改密/0正常''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
