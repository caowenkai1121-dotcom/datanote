# 05-Claude 实施任务清单

Claude 是实施者，请严格按本清单从 P0 到 P2 分批修复。每个任务都要小步提交式推进：先读相关代码，再做最小改动，再验证。

## 通用实施规则

- 不部署。
- 不写任何远程连接信息或凭据。
- 不一次性重写大文件。
- 不新增依赖。
- 每项修复都要说明验证命令或复现步骤。
- UI 改动必须遵守 `docs/设计规范.md`。

## P0 必须优先闭环

### CLD-P0-01 启动鉴权失败改为 fail-closed

角色来源：【Design】

证据：

- `src/main/resources/static/workspace.html:14036` `dnBootGate`。
- `src/main/resources/static/workspace.html:14037` `/api/auth/status`。
- `src/main/resources/static/workspace.html:14042` `/api/rbac/me`。
- `src/main/resources/static/workspace.html:7802` 路由守卫。

实施边界：

- 只改启动门禁和错误渲染相关逻辑。
- 不重写路由系统。

验收：

- 模拟 `/api/auth/status` 失败，页面不进入工作台，显示可重试错误。
- 模拟 `/api/rbac/me` 失败，页面不进入工作台，显示权限加载失败。
- 开放模式仍可进入工作台。

### CLD-P0-02 WebSocket 私有化

角色来源：【Architect】【Dev-A】

证据：

- `src/main/java/com/datanote/common/LogBroadcastService.java:40`、`58`、`88`。
- `src/main/java/com/datanote/domain/integration/HiveDdlController.java:503`。
- `src/main/resources/static/workspace.html:17635` 到 `17653`。

实施边界：

- 先处理任务日志、任务状态、通知、SQL 日志四类 topic。
- 保留旧前端显示能力，但订阅改为当前用户/当前任务私有通道。

验收：

- 用户 A 的任务日志，用户 B 不能收到。
- SQL 日志只对执行者可见。

### CLD-P0-03 旧 DataX/DDL 入口补归属和权限校验

角色来源：【Architect】

证据：

- `src/main/java/com/datanote/domain/integration/DataxController.java:93`。
- `src/main/java/com/datanote/domain/integration/DataxController.java:124`。
- `src/main/java/com/datanote/domain/integration/service/SyncJobService.java:87`。

实施边界：

- 不删除旧接口。
- 先新增统一 guard 或复用 `SyncJobService` 校验。

验收：

- 无源/目标数据源权限时，旧 DataX 入口拒绝运行。
- jobId 只能由创建者或有权限用户执行。

### CLD-P0-04 SQL SSE executionId 绑定用户

角色来源：【Architect】

证据：

- `src/main/java/com/datanote/domain/integration/HiveDdlController.java:235`。
- `src/main/java/com/datanote/domain/integration/HiveDdlController.java:263`。
- `src/main/java/com/datanote/domain/integration/HiveDdlController.java:284`。

实施边界：

- 只改 pending SQL 存储结构和消费校验。
- 不重写 SQL 执行器。

验收：

- 用户 B 不能消费用户 A 创建的 executionId。
- executionId 只能消费一次。

### CLD-P0-05 AI table_data 接入统一数据读取策略

角色来源：【Architect】【Domain】

证据：

- `src/main/java/com/datanote/platform/ai/agent/tool/impl/TableDataTool.java:16`、`54`。
- `src/main/java/com/datanote/domain/governance/AssetDetailService.java:179`。
- `src/main/java/com/datanote/domain/datasource/DatasourceExploreService.java:244`。
- `src/main/java/com/datanote/platform/ai/agent/analysis/AnalysisQueryService.java:42`。

实施边界：

- 先让 `sampleRows` 复用现有脱敏/ACL 能力。
- 再抽统一 `DataReadPolicyService`，不要一次性改全仓。

验收：

- 受限表被 AI 请求样例时拒绝。
- 有脱敏策略的列不会明文返回。

### CLD-P0-06 删除或补齐“即将完善”入口

角色来源：【Product】【Domain】

证据：

- `src/main/resources/static/js/project.js:651`。

实施边界：

- 只处理项目模块对应 tab。
- 不重构整个项目空间。

验收：

- 用户界面不再出现“即将完善”。
- 若保留入口，必须有真实空态、动作和错误态。

## P1 高优先级

### CLD-P1-01 启用 CSRF 或等价防护

证据：

- `src/main/java/com/datanote/platform/config/SecurityConfig.java:84`。

验收：

- 无 CSRF token 的写请求返回 403。
- 登录、登出、AI SSE 等必要例外有明确 ignore 规则和测试。

### CLD-P1-02 AI 审批决定补会话归属

证据：

- `src/main/java/com/datanote/platform/ai/agent/web/AiAgentController.java:167` 到 `187`。

验收：

- 有权限但非 session owner 的用户不能审批他人 Agent 操作。

### CLD-P1-03 Shell 执行生产禁用或强约束

证据：

- `src/main/java/com/datanote/domain/orchestration/TaskExecutionService.java:64`。
- `src/main/java/com/datanote/domain/orchestration/TaskExecutionService.java:388`。

验收：

- 默认配置 Shell 类型不可执行。
- 如启用，必须命中独立权限、审批和审计。

### CLD-P1-04 SQL 权限拆分

证据：

- `src/main/java/com/datanote/platform/iam/PermInterceptor.java:94`。
- `src/main/java/com/datanote/domain/integration/HiveDdlController.java:70`。

验收：

- SELECT/WITH、DDL、DML、高危 SQL 权限分离。
- DROP/TRUNCATE/GRANT 默认拒绝或审批。

### CLD-P1-05 DataX 临时 job 文件 finally 清理

证据：

- `src/main/java/com/datanote/domain/integration/DataxService.java:22`。
- `src/main/java/com/datanote/domain/orchestration/TaskExecutionService.java:312` 到 `325`。

验收：

- `runJob` 抛异常后临时 job 文件也被删除。
- 日志不输出明文密码。

### CLD-P1-06 统一旧弹层

证据：

- `src/main/resources/static/workspace.html:5443`。
- `src/main/resources/static/workspace.html:5491`。
- `src/main/resources/static/workspace.html:5499`。
- `src/main/resources/static/workspace.html:2975`、`3005`、`3030`、`3055`、`3067`、`6204`、`15477`、`15982`。

验收：

- 破坏性确认使用 `DN.confirm`。
- 输入型操作改为抽屉或统一表单弹窗。
- 键盘焦点可进入弹层并返回触发按钮。

### CLD-P1-07 权限不足页和权限说明

证据：

- `src/main/resources/static/workspace.html:7805`。
- `src/main/resources/static/workspace.html:12017`。

验收：

- 深链无权限显示缺失权限点。
- 命令面板或入口能解释无权限原因。

### CLD-P1-08 小屏顶栏收纳

证据：

- `src/main/resources/static/workspace.html:110`。
- `src/main/resources/static/css/app.css:112`。

验收：

- 375px、768px 宽度下顶栏不横向溢出。
- 命令面板和用户入口可访问。

### CLD-P1-09 Data ACL 默认策略收紧

证据：

- `src/main/java/com/datanote/platform/iam/DataAclService.java:44`。
- `src/main/java/com/datanote/platform/iam/DataAclService.java:106`。

验收：

- 受保护资源 `resourceId == null` 不再直接放行。
- 公开资源通过显式 PUBLIC 或等价规则表达。

## P2 持续收敛

- CLD-P2-01：前端拆出 `api-client.js`、`router.js`、`ws-client.js`，每次只迁一个能力。
- CLD-P2-02：metadata/datamodel/schema/project tags 等接口逐步引入 DTO。
- CLD-P2-03：`ARCHITECTURE.md` 更新为真实边界。
- CLD-P2-04：`ConnectionManager` extraParams 增加白名单 sanitizer。
- CLD-P2-05：Redis `keys(prefix + "*")` 改 SCAN 或 key 索引集合。
- CLD-P2-06：隐藏模块增加显式重定向说明，避免幽灵页面。
- CLD-P2-07：同步详情小屏断点、旧内联样式、emoji 图标逐步清理。
