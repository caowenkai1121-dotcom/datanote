-- 98_ai_industry.sql —— AI 行业画像/行业经验: 业务流程SOP库 + 历史版本
-- 行业画像(蒸馏正文)复用 dn_ai_project_profile, profile_key='industry_global' 或 'industry_<业务域>', 无需新表。
-- 现有库迁移: 重复执行报已存在(可忽略)。

-- 业务流程 SOP / 行业经验技能(单组织按业务域分段): 标准业务流、报表口径、加工链路、坑
CREATE TABLE IF NOT EXISTS dn_ai_industry_sop (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    domain       VARCHAR(64)  NOT NULL DEFAULT 'global' COMMENT '业务域(如 销售/库存/财务/会员; global=通用)',
    sop_type     VARCHAR(24)  NOT NULL DEFAULT 'flow' COMMENT 'flow=业务流程 report=报表开发 caliber=指标口径 pitfall=坑/注意 glossary=术语',
    title        VARCHAR(255) NOT NULL COMMENT '标题(简明, 供召回匹配)',
    content      LONGTEXT     DEFAULT NULL COMMENT '正文: 标准步骤/口径/SQL模板/注意事项(Markdown)',
    trigger_hint VARCHAR(500) DEFAULT NULL COMMENT '触发词/适用场景(供按用户意图召回)',
    source       VARCHAR(16)  NOT NULL DEFAULT 'taught' COMMENT 'harvest=元数据归纳 learned=实战沉淀 taught=人工教学',
    status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active 生效 / draft 草稿待确认 / archived 归档',
    version      INT          NOT NULL DEFAULT 1 COMMENT '当前版本号',
    hit_count    INT          NOT NULL DEFAULT 0 COMMENT '召回命中次数(裁剪/排序用)',
    created_by   VARCHAR(128) DEFAULT NULL COMMENT '创建/最后修改人',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_domain_status (domain, status),
    KEY idx_type (sop_type),
    KEY idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 业务流程SOP/行业经验(按业务域)';

-- SOP 版本历史(对话式纠正/合并/编辑留痕, 支持回滚审计; 借鉴 colleague-skill version_manager)
CREATE TABLE IF NOT EXISTS dn_ai_industry_sop_hist (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    sop_id      BIGINT       NOT NULL COMMENT '所属 SOP id',
    version     INT          NOT NULL COMMENT '该快照版本号',
    title       VARCHAR(255) DEFAULT NULL COMMENT '快照标题',
    content     LONGTEXT     DEFAULT NULL COMMENT '快照正文',
    op          VARCHAR(16)  DEFAULT NULL COMMENT 'create/edit/correct/merge/archive',
    editor      VARCHAR(128) DEFAULT NULL COMMENT '操作人',
    snapshot_at DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (id),
    KEY idx_sop (sop_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 业务流程SOP 版本历史';
