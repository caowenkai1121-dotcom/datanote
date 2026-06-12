-- RBAC 加固: 用户最后登录时间(幂等)
USE datanote;

-- dn_user 增 last_login_at 列(MySQL 8 支持 IF NOT EXISTS; 老版本若报错可手动判列)
ALTER TABLE dn_user ADD COLUMN IF NOT EXISTS last_login_at DATETIME DEFAULT NULL COMMENT '最后登录时间' AFTER status;
