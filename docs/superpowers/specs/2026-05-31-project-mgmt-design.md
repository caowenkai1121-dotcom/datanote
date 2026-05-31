# 项目管理模块 — 总体设计（用户已锁定范围）

> 状态：设计稿（用户确认：全面范围 / 关联表 / 轻量审批流 / 软控制）
> 红线：单 JAR / Java 8 / Spring Boot 2.7 / MyBatis-Plus / 非破坏式（全新表，不改现有资产表）/ 复用 RBAC(dn_user)+审计 / UI 用全局 dbsync 风格(ops-layout/g-modal/ds-form/KPI 磁贴)，不依赖 #govModuleContent 作用域 / 极简不过度（剔除 SQL 环境改写、成本、灰度、多级审批）。

## 一、背景（调研结论）

现有「项目管理」(viewProject) 是空占位：项目空间/成员权限/发布管理 三页签全"规划中"，无后端。平台已有完整 RBAC（dn_user/dn_role/dn_user_role/dn_role_perm + RbacService + 登录态 SecurityContextHolder/AuthController）、可编排资产（dn_sync_job/dn_script/dn_datasource/dn_quality_rule 均含 createdBy）、成熟分组/关联表范式（dn_sync_folder、dn_group+dn_group_member、dn_baseline_task、dn_sync_job_dependency）、审计（AuditLogService 异步非阻断）。迁移最大 47，新表从 48 起。

## 二、锁定决策

| # | 决策 | 取值 |
|---|---|---|
| 1 | 范围 | 全面：CRUD+空间列表、概览大盘、成员与项目角色、资产纳管、设置、发布管理(轻量审批) |
| 2 | 资产关联 | 关联表 dn_project_asset（不动现有资产表，多对多，零迁移风险） |
| 3 | 发布管理 | 轻量审批流：版本快照 + 单级审批(提交→通过/驳回→发布→可回滚) + 发布记录 |
| 4 | 权限 | 软控制：项目成员/角色用于展示/归属/成员管理/审批人；不强拦截现有资产 API；复用全局 RBAC 登录态 |

## 三、数据模型（sql/48_project_management.sql，全部 CREATE IF NOT EXISTS 幂等）

- **dn_project**：id / project_code(唯一) / project_name / description / project_type(GENERAL/DATASYNC/DEVSQL/HYBRID) / env(DEV/PROD/MIXED) / owner(username) / sensitivity(NORMAL/SENSITIVE) / tags(逗号串) / status(ACTIVE/ARCHIVED/DELETED) / created_by / created_at / updated_at / archived_at / deleted_at。软删除。
- **dn_project_member**：id / project_id / username / project_role(OWNER/ADMIN/DEVELOPER/OPS/VIEWER) / added_by / created_at。uk(project_id,username)。
- **dn_project_asset**：id / project_id / asset_type(SYNC_JOB/SCRIPT/DATASOURCE/QUALITY_RULE) / asset_id / asset_name(冗余展示名) / created_by / created_at。uk(project_id,asset_type,asset_id)。
- **dn_project_release**：id / project_id / version_no(项目内自增) / title / content(快照文本) / target_env / status(PENDING/APPROVED/REJECTED/RELEASED/ROLLED_BACK) / submitted_by / submitted_at / approver / approved_at / approve_comment / released_at / created_at。

成员/owner 一律用 username（与 dn_group_member、createdBy 约定一致，免 join）。审计复用既有 AuditService（自动审计 /api/* 写操作入 dn_audit_log）；项目活动流由成员/资产/发布三表 created_at 合成（准确、无外部依赖）。

## 四、后端（com.datanote.project 包）

- model：DnProject/DnProjectMember/DnProjectAsset/DnProjectRelease（@Data @TableName，BaseMapper）。
- service：
  - ProjectService：CRUD + validate(名/code 唯一、非空) + 自动生成 project_code + 创建即把 owner 写入成员(OWNER) + archive/softDelete + overview 聚合(各类型资产数/成员数/发布数/最近活动)。
  - ProjectMemberService：add/remove/changeRole/list；项目角色→权限矩阵（纯函数 ProjectRoles）。
  - ProjectAssetService：bind/unbind/list + 可绑定资产候选(查各资产表)。
  - ProjectReleaseService：submit/approve/reject/release/rollback + list；状态机 ReleaseState（纯函数 canTransition）。
- controller：ProjectController（/api/project/**）。R 统一返回，IllegalArgumentException→R.fail。
- 纯函数单测：project_code 生成、ProjectRoles 角色权限矩阵、ReleaseState 状态机、overview 聚合。

## 五、前端（workspace.html viewProject，全局 dbsync 风格）

重写三页签：
- **项目空间**：KPI 磁贴(总项目/活跃/归档/我负责) + 项目列表(名/code/类型/env/owner/状态/资产数/成员数/创建) + 新建/编辑(g-modal 或抽屉 ds-form) + 行操作(详情/编辑/归档/删除)。点击进**项目详情抽屉**(子页签：概览大盘 / 资产 / 成员 / 发布 / 设置)。
- **成员权限**：项目角色→权限矩阵展示 + 选项目快速管理成员。
- **发布管理**：跨项目发布列表 + 待审批，提交/审批/发布/回滚。

复用：ops-layout/ops-sidebar(已占位)、ops-dashboard、btn/btn-primary/btn-sm、g-modal(对话)、dbsync 抽屉式(详情)、ds-form-row/dbsync-form-*(表单)、dbsync KPI 磁贴范式、表格(dbsync-exec-table 风格)。

## 六、里程碑（每个独立交付+对抗审查+部署+推送）

- **PM-M1** 项目 CRUD + 空间列表 + KPI 磁贴 + 新建/编辑/归档/软删。
- **PM-M2** 成员与项目角色（add/remove/role + 角色权限矩阵 + 成员权限页签）。
- **PM-M3** 资产纳管（dn_project_asset 绑定/解绑 + 候选选择器 + 详情资产页签）。
- **PM-M4** 项目概览大盘（聚合 + 活动流 + 详情概览页签）。
- **PM-M5** 发布管理（版本+单级审批+回滚+发布页签+跨项目发布中心）。

## 七、测试与质量

JUnit5 纯函数单测（code 生成/角色矩阵/状态机/聚合）；JS node --check；每里程碑多智能体对抗审查→整改→部署→验证(服务 active + CDC job2 RUNNING 不受扰)→推送 caowen。

## 八、风险与护栏

1. 非破坏：全新表 + 关联表，不改现有资产表/RBAC，零迁移风险。
2. 软控制：不动现有资产 API 鉴权，避免越权回归。
3. 软删除 + 归档，不物理删项目数据。
4. 发布状态机用纯函数 + 单测，防非法流转。
