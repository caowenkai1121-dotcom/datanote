-- R122: 数据开发根目录规范化 — 只保留 数据源/ODS/DWD/ADS/DM/脚本
-- (live 已用脚本执行; 此文件为声明式记录, 按 layer 幂等)
-- 1) DWS 层脚本迁回 "脚本" 根目录
UPDATE dn_script SET folder_id = (SELECT id FROM (SELECT id FROM dn_script_folder WHERE parent_id=0 AND folder_name='脚本' LIMIT 1) t)
  WHERE folder_id IN (SELECT id FROM (SELECT id FROM dn_script_folder WHERE parent_id=0 AND layer='DWS') x);
-- 2) 删 DWS 层 + AI 测试空目录
DELETE FROM dn_script_folder WHERE parent_id=0 AND layer='DWS';
DELETE FROM dn_script_folder WHERE parent_id=0 AND (layer IS NULL OR layer='') AND folder_name LIKE 'AI%';
-- 3) 新增 DM 层(数据集市)
INSERT INTO dn_script_folder(folder_name, layer, parent_id, sort_order, created_at)
  SELECT 'DM层','DM',0,4,NOW() FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM dn_script_folder WHERE parent_id=0 AND layer='DM');
-- 4) 重排序: 数据源0/ODS1/DWD2/ADS3/DM4/脚本5
UPDATE dn_script_folder SET sort_order=0 WHERE parent_id=0 AND layer='数据源';
UPDATE dn_script_folder SET sort_order=1 WHERE parent_id=0 AND layer='ODS';
UPDATE dn_script_folder SET sort_order=2 WHERE parent_id=0 AND layer='DWD';
UPDATE dn_script_folder SET sort_order=3 WHERE parent_id=0 AND layer='ADS';
UPDATE dn_script_folder SET sort_order=4 WHERE parent_id=0 AND layer='DM';
UPDATE dn_script_folder SET sort_order=5 WHERE parent_id=0 AND layer='脚本';
