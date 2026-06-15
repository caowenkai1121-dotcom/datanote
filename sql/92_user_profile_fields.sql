-- R121: 加强用户管理 — dn_user 增加用户档案字段(邮箱/手机/部门/岗位/工号/备注)
-- 注: MySQL 不支持 ADD COLUMN IF NOT EXISTS, 运维脚本判存幂等
ALTER TABLE dn_user ADD COLUMN email       VARCHAR(100) NULL COMMENT '邮箱'      AFTER must_change_pwd;
ALTER TABLE dn_user ADD COLUMN phone       VARCHAR(20)  NULL COMMENT '手机号'    AFTER email;
ALTER TABLE dn_user ADD COLUMN department  VARCHAR(64)  NULL COMMENT '部门'      AFTER phone;
ALTER TABLE dn_user ADD COLUMN position    VARCHAR(64)  NULL COMMENT '岗位/职位' AFTER department;
ALTER TABLE dn_user ADD COLUMN employee_id VARCHAR(64)  NULL COMMENT '员工工号'  AFTER position;
ALTER TABLE dn_user ADD COLUMN remark      VARCHAR(500) NULL COMMENT '备注'      AFTER employee_id;
