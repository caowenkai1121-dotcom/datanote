-- 97_ai_profiles.sql —— AI 长久记忆: 用户画像(用户隔离) + 项目画像(全局) + 审批友好摘要列
-- 现有库迁移: 重复执行报已存在(可忽略)。

-- 用户画像(用户隔离): 每日由该用户沉淀经验 LLM 蒸馏, 注入 agent 上下文让其越来越懂用户
CREATE TABLE IF NOT EXISTS dn_ai_user_profile (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_name   VARCHAR(128) NOT NULL COMMENT '用户(隔离键)',
    content     LONGTEXT     DEFAULT NULL COMMENT '画像正文(角色/偏好/常用操作/数据域/风格, 已蒸馏精简)',
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 用户画像(用户隔离的长久记忆)';

-- 项目画像(全局记忆): 跨用户对"项目本身"(数据域/常用表/命名规范/常见流程/坑)的长久积累; 另存每日汇总日期 marker
CREATE TABLE IF NOT EXISTS dn_ai_project_profile (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    profile_key VARCHAR(64)  NOT NULL COMMENT "画像键: 'global'=项目全局画像; '__digest_date__'=每日汇总占位",
    content     LONGTEXT     DEFAULT NULL COMMENT '画像正文 / marker 值',
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key (profile_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 项目画像(全局长久记忆)';

-- 审批友好摘要: 把 create_ods_table+{json} 转成人话"把 xx 表全量同步到数仓ODS层", 审批卡展示
ALTER TABLE dn_ai_approval
    ADD COLUMN action_summary VARCHAR(500) DEFAULT NULL COMMENT '人类可读的操作摘要(审批卡展示)' AFTER args_json;
