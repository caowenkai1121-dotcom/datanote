-- RBAC 标准角色预置(幂等, 可重复执行)
-- 与 PermCatalog.java 权限点清单对齐。ADMIN 角色已在 36_rbac.sql 预置(perm_code='*')。
USE datanote;

-- ============ 标准角色 ============
INSERT INTO dn_role (role_code, role_name, description) VALUES
  ('DATA_DEV',    '数据开发', '数据开发/运维/同步/指标 全流程')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), description=VALUES(description);
INSERT INTO dn_role (role_code, role_name, description) VALUES
  ('DATA_GOV',    '数据治理', '治理/质量/标准/主数据/审计')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), description=VALUES(description);
INSERT INTO dn_role (role_code, role_name, description) VALUES
  ('PROJECT_MGR', '项目经理', '项目管理与发布审批 + 各模块查看')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), description=VALUES(description);
INSERT INTO dn_role (role_code, role_name, description) VALUES
  ('VIEWER',      '只读用户', '仅查看各模块, 无任何写操作')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), description=VALUES(description);

-- ============ 数据开发 权限点 ============
SET @rid := (SELECT id FROM dn_role WHERE role_code='DATA_DEV');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES
  (@rid,'home:view'),
  (@rid,'develop:view'),(@rid,'develop:edit'),(@rid,'develop:run'),
  (@rid,'operations:view'),(@rid,'operations:schedule'),(@rid,'operations:backfill'),(@rid,'operations:baseline'),
  (@rid,'catalog:view'),(@rid,'catalog:edit'),
  (@rid,'datamodel:view'),(@rid,'datamodel:edit'),
  (@rid,'dbsync:view'),(@rid,'dbsync:edit'),(@rid,'dbsync:run'),
  (@rid,'metrics:view'),(@rid,'metrics:edit'),
  (@rid,'assistant:view'),(@rid,'assistant:use');

-- ============ 数据治理 权限点 ============
SET @rid := (SELECT id FROM dn_role WHERE role_code='DATA_GOV');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES
  (@rid,'home:view'),
  (@rid,'governance:view'),(@rid,'governance:quality'),(@rid,'governance:issue'),(@rid,'governance:standard'),(@rid,'governance:manage'),(@rid,'governance:audit'),
  (@rid,'catalog:view'),
  (@rid,'datamodel:view'),(@rid,'datamodel:edit'),(@rid,'datamodel:approve'),
  (@rid,'mdm:view'),(@rid,'mdm:manage'),(@rid,'mdm:approve'),
  (@rid,'metrics:view'),
  (@rid,'assistant:view'),(@rid,'assistant:use');

-- ============ 项目经理 权限点 ============
SET @rid := (SELECT id FROM dn_role WHERE role_code='PROJECT_MGR');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES
  (@rid,'home:view'),
  (@rid,'project:view'),(@rid,'project:manage'),(@rid,'project:approve'),(@rid,'project:all-data'),
  (@rid,'develop:view'),(@rid,'operations:view'),(@rid,'catalog:view'),
  (@rid,'governance:view'),(@rid,'metrics:view'),(@rid,'dbsync:view'),
  (@rid,'assistant:view'),(@rid,'assistant:use');

-- ============ 只读用户 权限点(全部 :view) ============
SET @rid := (SELECT id FROM dn_role WHERE role_code='VIEWER');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES
  (@rid,'home:view'),(@rid,'develop:view'),(@rid,'operations:view'),(@rid,'catalog:view'),
  (@rid,'governance:view'),(@rid,'mdm:view'),(@rid,'dbsync:view'),(@rid,'metrics:view'),
  (@rid,'project:view'),(@rid,'assistant:view');
