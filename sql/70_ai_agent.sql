-- 70_ai_agent.sql —— 天工·自由意志数据智能体(天工司辰) 数据模型
-- M1 启用: dn_ai_session / dn_ai_step (只读最小闭环, 零写副作用)
-- M2 启用: dn_ai_approval (高危写动作审批留痕)
-- M4 延后: dn_ai_memory_skill (自学习经验/技能, 红线: 永不提权)

-- ===== M1: Agent 会话主表 =====
CREATE TABLE IF NOT EXISTS dn_ai_session (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)  NOT NULL COMMENT '会话UUID(对外标识)',
    user_name       VARCHAR(128) DEFAULT NULL COMMENT '发起用户',
    goal_intent     TEXT         DEFAULT NULL COMMENT '本会话目标/意图',
    status          VARCHAR(24)  NOT NULL DEFAULT 'running' COMMENT 'running/paused/wait_approval/done/blocked/cancelled',
    interrupt_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '中断标志(轮询载体, 替代SSE)',
    steer_text      TEXT         DEFAULT NULL COMMENT '中途转向插话(下一轮并入context)',
    plan_json       LONGTEXT     DEFAULT NULL COMMENT 'M3流水线规划快照',
    auto_approve    TINYINT      NOT NULL DEFAULT 0 COMMENT '本任务批量自动批准写操作(1=后续写操作免逐个审批; done时清0)',
    budget_steps_used INT        NOT NULL DEFAULT 0 COMMENT '已消耗步数',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user (user_name),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 会话主表';

-- ===== M1: Agent 步骤表(消息流+工具调用日志+计划记录 三合一) =====
CREATE TABLE IF NOT EXISTS dn_ai_step (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)  NOT NULL COMMENT '所属会话UUID',
    seq             INT          NOT NULL DEFAULT 0 COMMENT '步序号',
    step_type       VARCHAR(16)  NOT NULL DEFAULT 'SKILL_CALL' COMMENT 'PLAN/SKILL_CALL/REPLAN/FINAL',
    role            VARCHAR(16)  DEFAULT NULL COMMENT 'user/assistant/tool',
    content         LONGTEXT     DEFAULT NULL COMMENT '文本内容(sanitize后)',
    think_content   LONGTEXT     DEFAULT NULL COMMENT '模型思考',
    skill_name      VARCHAR(64)  DEFAULT NULL COMMENT '工具名',
    skill_group     VARCHAR(64)  DEFAULT NULL COMMENT '工具分组',
    args_json       TEXT         DEFAULT NULL COMMENT '工具入参JSON',
    result_status   VARCHAR(16)  DEFAULT NULL COMMENT 'ok/error/pending',
    result_type     VARCHAR(32)  DEFAULT NULL COMMENT '错误类型(unknown_tool/bad_arguments/not_found/forbidden/exec_failed/need_approval)',
    result_data     LONGTEXT     DEFAULT NULL COMMENT '工具结果(sanitize后)',
    read_only       TINYINT      DEFAULT 1 COMMENT '是否只读工具',
    risk_level      VARCHAR(8)   DEFAULT 'LOW' COMMENT 'LOW/MEDIUM/HIGH',
    latency_ms      BIGINT       DEFAULT NULL COMMENT '本步耗时ms',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_session_seq (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 步骤/工具调用/计划 三合一';

-- ===== M2: 高危/写动作审批留痕(审批人/时间/参数不可空) =====
CREATE TABLE IF NOT EXISTS dn_ai_approval (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)  NOT NULL COMMENT '所属会话UUID',
    step_seq        INT          DEFAULT NULL COMMENT '对应步序号',
    skill_name      VARCHAR(64)  NOT NULL COMMENT '待审批工具',
    args_json       TEXT         DEFAULT NULL COMMENT '工具入参',
    risk_level      VARCHAR(8)   DEFAULT 'HIGH' COMMENT '风险级',
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
    decided_by      VARCHAR(128) DEFAULT NULL COMMENT '审批人',
    decided_at      DATETIME     DEFAULT NULL COMMENT '审批时间',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_session (session_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 高危动作审批(M2)';

-- ===== M4: 自学习经验/技能(延后启用; 红线: 永不影响护栏/权限/危险级) =====
CREATE TABLE IF NOT EXISTS dn_ai_memory_skill (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    type            VARCHAR(16)  NOT NULL DEFAULT 'memory' COMMENT 'memory/skill',
    title           VARCHAR(255) DEFAULT NULL COMMENT '标题',
    content         LONGTEXT     DEFAULT NULL COMMENT '陈述事实/技能内容',
    trigger_hint    VARCHAR(255) DEFAULT NULL COMMENT '触发线索',
    owner           VARCHAR(128) DEFAULT NULL COMMENT '归属',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/archived',
    hit_count       INT          NOT NULL DEFAULT 0 COMMENT '命中次数',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_type_status (type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Agent 自学习经验/技能(M4)';
