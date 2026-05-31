# 项目管理 大版本迭代 V2 — 设计稿（≥10 大功能）

> 红线：单JAR/Java8/SpringBoot2.7/MyBatis-Plus；非破坏(全新表+端点+viewProject增量,不动既有)；复用 RBAC/审计(dn_audit_log)/血缘(dn_lineage_edge)/marked.js；UI 全局 dbsync 风格；极简不过度(不做告警引擎/甘特/字段级血缘/趋势预测/Wiki版本回滚 v1)。迁移从 50 起。成员/owner 用 username。软控制鉴权(开放态)。

## 已有(V1)：项目CRUD/检索/模板预填、概览大盘、5角色成员、资产纳管+反查、发布审批+回滚、设置(配额+环境参数)。

## V2 新增 13 大功能 / 8 里程碑

- **PM2-M1 标签体系 + 收藏/置顶/最近访问**(sql/50)：dn_project_tag/dn_project_tag_mapping(标签CRUD+打标+按标签筛选)；dn_project_favorite(收藏/置顶,username+project)；dn_project_access_log(最近访问,详情打开时记)。项目空间列表加 收藏星标/标签筛选/收藏优先与最近访问排序。
- **PM2-M2 项目活动审计 + 项目健康分**：活动=查 dn_audit_log(path LIKE /api/project/{id})时间线(筛选 操作类型/时间)，复用无新表；健康分=按 资产覆盖/成员完备/发布节奏/质量/活跃 五维即时计算 0-100(纯函数 ProjectHealthScorer 可测)，无新表。详情加 活动/健康 子页签。
- **PM2-M3 任务待办 + 里程碑**(sql/51)：dn_project_task(标题/描述/指派/优先级/状态/截止/里程碑)、dn_project_milestone(名/起止/描述)。详情 任务 子页签：按状态(待办/进行/完成)看板式列表 + 里程碑分组 + 进度统计(无甘特)。
- **PM2-M4 公告 + 成员邀请/加入审批**(sql/52)：dn_project_announcement(标题/内容/优先级/过期)+dn_project_announcement_read(已读)；dn_project_invite(token/角色/状态/过期,发起→同意/拒绝)。详情 公告 子页签 + 成员页签加邀请。
- **PM2-M5 项目文档 Wiki**(sql/53)：dn_project_wiki_page(标题/内容md/parentId/order)。详情 文档 子页签：页面树 + Markdown 编辑(textarea)/预览(marked.js)。无版本史 v1。
- **PM2-M6 项目血缘地图**：复用 dn_lineage_edge，按项目绑定资产(同步任务源/目标表、表名)取相关血缘边子图，列表+简单连线展示。详情 血缘 子页签。无新表。
- **PM2-M7 项目模板管理**(sql/54)：dn_project_template(名/类型/描述/config_json)。把项目存为模板(快照 类型/env/标签/成员角色/设置)；新建从模板初始化(种子成员/设置)。升级 V1 前端预填为持久化模板。新增 模板管理 顶级页签或入口。
- **PM2-M8 工作台首页 + 多项目对比**：/api/project/home 聚合(我负责/收藏/最近访问/我的待办任务/待我审批发布)；新增「工作台」顶级页签。多项目对比 /api/project/compare(选多项目比 资产/成员/发布/健康)，对比页签。

## 后端：com.datanote.{model,mapper,service,controller}，扩 ProjectController + 新 Service(Tag/Favorite/Activity/Health/Task/Announcement/Invite/Wiki/Lineage/Template/Home)。纯函数单测(健康分/模板快照/标签)。

## 前端：viewProject 详情抽屉 projLoadPane 分发新增子页签(活动/健康/任务/公告/文档/血缘)；顶级页签加 工作台/模板管理/对比；列表加 标签筛选+收藏星标。复用 dbsync-drawer/g-modal/ds-form/dbsync-exec-table/KPI磁贴/marked.js。

## 交付：每里程碑提交；部署检查点 M1-M4(sql50-52)、M5-M8(sql53-54);末尾多智能体对抗审查→整改→E2E→推送 caowen。线上 CDC job2 每次部署验证 RUNNING。
