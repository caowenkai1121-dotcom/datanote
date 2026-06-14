-- 数据源权限独立管控(幂等, 可重复执行)
-- 将数据源读写从 develop:edit 拆出为 datasource:view / datasource:edit, 并补 develop:approve(脚本上线审批)。
-- 与 PermCatalog.java / PermInterceptor.java 对齐。ADMIN(perm_code='*') 天然覆盖, 无需补。
USE datanote;

-- 数据开发: 数据源读写 + 脚本上线审批(自批由 ScriptApprovalService 记录级拦截, 角色给 approve 安全)
SET @rid := (SELECT id FROM dn_role WHERE role_code='DATA_DEV');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES
  (@rid,'datasource:view'),(@rid,'datasource:edit'),(@rid,'develop:approve');

-- 数据治理 / 项目经理 / 只读用户: 数据源只读(探查源库/选源建模需要, 不授写)
SET @rid := (SELECT id FROM dn_role WHERE role_code='DATA_GOV');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES (@rid,'datasource:view');
SET @rid := (SELECT id FROM dn_role WHERE role_code='PROJECT_MGR');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES (@rid,'datasource:view');
SET @rid := (SELECT id FROM dn_role WHERE role_code='VIEWER');
INSERT IGNORE INTO dn_role_perm (role_id, perm_code) VALUES (@rid,'datasource:view');
