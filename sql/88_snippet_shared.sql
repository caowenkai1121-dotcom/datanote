-- SQL 片段库团队共享: 片段可标记为团队共享, 团队成员可见可用(仅创建人/admin 可改删)。
-- 幂等(information_schema 判断, MySQL 8 不支持 ADD COLUMN IF NOT EXISTS)。
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'dn_sql_snippet' AND column_name = 'shared');
SET @s := IF(@c = 0,
    'ALTER TABLE dn_sql_snippet ADD COLUMN shared TINYINT DEFAULT 0 COMMENT ''是否团队共享: 0私有 1共享''',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
