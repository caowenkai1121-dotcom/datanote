-- R35: 表元数据挂主题域 —— dn_table_meta 增加 subject_id 列(幂等)
-- 完成 R25 悬挂:主题树→按主题域筛资产 的数据基础;资产可归属主题域。
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_table_meta' AND COLUMN_NAME = 'subject_id');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_table_meta ADD COLUMN subject_id BIGINT DEFAULT NULL COMMENT ''所属主题域(dn_subject.id)''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
