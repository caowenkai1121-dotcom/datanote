-- SQL 片段库: 数据开发常用 SQL 片段沉淀与一键插入(按创建人隔离)
CREATE TABLE IF NOT EXISTS dn_sql_snippet (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL COMMENT '片段名(同一用户下唯一)',
    content     MEDIUMTEXT   NOT NULL COMMENT 'SQL 内容',
    description VARCHAR(512)          DEFAULT NULL COMMENT '说明',
    category    VARCHAR(64)           DEFAULT NULL COMMENT '分类',
    use_count   INT          NOT NULL DEFAULT 0 COMMENT '插入次数',
    created_by  VARCHAR(64)           DEFAULT NULL COMMENT '创建人',
    created_at  DATETIME              DEFAULT NULL,
    updated_at  DATETIME              DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_snippet_owner (created_by),
    UNIQUE KEY uk_snippet_owner_name (created_by, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 片段库';
