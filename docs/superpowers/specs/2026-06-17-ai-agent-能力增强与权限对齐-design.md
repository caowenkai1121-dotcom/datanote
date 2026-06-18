# AI Agent 能力增强与权限对齐 — 设计方案

- 日期: 2026-06-17
- 状态: 待用户评审
- 作者: 多角色团队(本次由 Architect/Domain/Product 主导设计)
- 范围: `com.datanote.platform.ai.agent.*` + `com.datanote.platform.iam.*`(新增重载) + 前端 `js/ai-agent.js` / `workspace.html`

---

## 一、背景与目标

AI Agent「天工司辰」已较成熟:44 工具(9 写 35 读)、RAG、向量库(Qdrant)/图库(Neo4j)、多智能体委派、cron 自治、上下文压缩、审批护栏、自学习记忆、文件上传下载等。本次业主提出在此基础上继续增强,核心诉求:

1. **完整能力覆盖**:AI agent 能做系统所有能做的事。
2. **权限对齐(最重要)**:agent 的执行权限必须与发起用户一致——用户有什么功能权限就只能做什么、用户有什么数据权限就只能查什么。
3. **能力/体验增强**:数据分析+图表可视化、PDF/Word 文档知识库 RAG、SSE 流式响应。

本方案把工作拆为 **5 个特性(E/D/A/B/C)**,并据"安全先行"重排优先级:**权限对齐(E)是地基,必须最先落地**;之后再放开更强的写工具(D),否则等于给 agent 超出用户授权的能力。

### 目标(可验收)

- agent 调用任何工具前,以**发起用户的 RBAC 功能权限**为边界:用户无该权限 → 硬拒绝,绝不可绕过。
- agent 查询/读取数据时,受**发起用户的数据级授权(DataAcl)**约束:用户看不到的资源 → 查不到/被过滤。
- 异步路径(子代理/cron/learn/review/resume)同样受上述约束(修复 ThreadLocal 失效陷阱)。
- 系统能做的动作(除永久基础设施禁区外)都有对应工具,且每个工具标注所需权限点。
- agent 能跑只读聚合分析并在对话中出图;能基于上传的 PDF/Word 文档做 RAG 问答;终答支持 SSE 流式打字机。

---

## 二、现状与约束(红线)

### 已核实的关键事实

- **权限模型**:`RbacService.getUserPermsByUsername(user)` → 权限点集合(角色权限 ∪ 用户直授,`*`=超管);`RbacService.hasPermission(perms, need)` 为纯静态判定(`*` 或精确匹配通过,空 need 拒)。
- **功能级鉴权**:`PermInterceptor.requiredPerm(method, path)` 把 HTTP 写/敏感读映射到 `PermCatalog` 权限点。`PermCatalog.ALL` 是权限点单一事实来源(模块:功能,如 `develop:edit`/`dbsync:run`/`governance:quality`/`mdm:approve` 等)。
- **数据级**:`DataAclService` 默认公开/黑名单模型——资源(`resourceType`+`resourceId`)有 ≥1 条授权即"受限",仅被授权主体(用户/角色)+ 超管 + `data:all` 可访问;无授权=公开。`canAccess(type,id)`、`deniedIds(type)`。
- **安全空缺(本次要修)**:agent 工具**直接调 domain 服务**,既绕过 `PermInterceptor`(只拦 HTTP),也绕过 controller 内的 DataAcl 过滤。当前 agent 的 `Guardrail` 只认 `readOnly()/risk()/DENY_NAMES`,**完全不查发起用户的 RBAC 权限**。
- **异步陷阱**:`CurrentUserUtil.currentUser()` 与 `DataAclService.bypass()` 读 `SecurityContextHolder`(ThreadLocal)。子代理(`aiChildExecutor`)/`@Async`(learn/review)/cron(`@Scheduled`)在别的线程跑 → ThreadLocal 空 → `currentUser()="anonymous"` → `bypass()=true` → 数据 ACL 静默失效。
- **身份已部分就位**:`AgentContext` 已带 `userName`(发起人)、`ip`、`role`、`sessionId`。Controller 在 HTTP 边界用 `currentUser()` 填入。cron 任务有 `owner` 字段;resume 取会话发起人(R73 已做)。
- **LLM 入口**:`AiAssistService.chat(userMessage, context)` 阻塞式 `HttpURLConnection`,`max_tokens=4096`,OpenAI 兼容(deepseek/百炼)与 Anthropic 两种格式。
- **图表地基**:`js/dn-common.js` 有纯 SVG 零依赖图表工厂 `DN.bars`/`DN.line`/`DN.donut`/`DN.radar`。
- **向量地基**:`EmbeddingService.embed/embedBatch`、`VectorStoreClient.upsert(points[{id,vector,payload}])`/`search(vector,kind,limit)`、`SemanticSearchService.search(query,kind,limit)`(支持 kind 过滤)、`VectorIndexService` 以 `payload.kind` 区分(table/column/glossary/metric/dataelement/wordroot/memory…)。
- **文件地基**:`AiFileService` 白名单(已含 pdf/docx)+ 20MB + UUID 存名 + owner 作用域;`readText` 已支持 csv/txt/json/md/xlsx(`XlsxTextExtractor` 零依赖)。
- **SQL 执行地基**:`com.datanote.domain.integration.util.SqlExecutor`;`AssetDetailService.sampleRows` 经 `ConnectionManager` 连源库/数仓(Doris)。`SqlParseTablesTool` 可解析 SQL 引用表。

### 必须守住的红线

1. **不改 `AiAssistService.chat(userMessage, context)` 签名**(SSE 走新增 `chatStream`)。
2. **永久基础设施禁区(业主拍板:始终硬禁,无视权限)**:凭据保存/修改、deploy 部署、修改 agent 护栏配置本身、不可逆群毁(drop/truncate/批量删)。与现有 `Guardrail.DENY_NAMES` 一致,不工具化。
3. **写操作仍走审批**(业主拍板:允许用户确认自己 agent 的动作,见 §三.5)。
4. **fail-closed**:鉴权/数据判定异常一律按"拒绝"处理,绝不 fail-open。脱敏 fail-closed 不变。
5. **身份字段恒取发起人**(createdBy/owner),绝不取模型输出。
6. **记忆/技能永不参与 Guardrail/PermGate**(防投毒提权)。
7. **零侵入既有 domain 服务**;`DataNoteApplication @MapperScan` 如需新 mapper 包须追加(否则启动失败)。
8. **PDFBox 是唯一新增依赖**(业主已授权),docx 走零依赖。

---

## 三、特性 E — 权限对齐(以发起用户身份+权限执行)【地基,最先做】

### 3.1 身份与权限在边界捕获(消除 ThreadLocal 依赖)

`AgentContext` 新增字段并在 Controller 边界填充,全程携带:

```
class AgentContext {
    String userName;          // 已有: 发起用户
    String ip; String role; String sessionId; // 已有
    Set<String> perms;        // 新增: 发起用户有效权限点集(角色∪直授, 含 '*')
    List<String> roles;       // 新增: 发起用户角色编码集(供 DataAcl 角色授权判定)
    boolean permsResolved;    // 新增: 是否已解析(避免重复查库)
}
```

- **HTTP 入口**(`AiAgentController` 各端点):构造 ctx 时 `perms = rbacService.getUserPermsByUsername(caller)`、`roles = rbacService.getUserRoleCodesByUsername(caller)`。开放模式(未设 `DATANOTE_PASSWORD`)按"全放行"语义填 `*`(与 `PermInterceptor` 开放模式一致)。
- **子代理**(`ChildAgentRunner`):子 ctx **继承父 caller/perms/roles**(子代理是同一用户的分身,权限不放大)。
- **cron**(`CronScheduler.runCron`):用 cron 任务 `owner` 解析 perms/roles(cron 以创建者身份执行;创建者权限变化后下次执行即生效)。
- **resume**(`AiAgentService.resume`):用会话发起人(R73 已取会话 writeCtx),补充 perms/roles 解析。
- **关键**:PermGate 与数据校验一律读 `ctx.perms/ctx.roles/ctx.userName`,**不读 `SecurityContextHolder`**,从根上修复异步陷阱。

### 3.2 功能级闸门 `PermGate`(与 Guardrail 并列的新闸门)

- `AiTool` 接口新增 **default 方法**:`default String requiredPerm() { return null; }`(null = 仅需登录,仿 PermInterceptor 普通 GET 语义)。**44 个工具零改动**;写工具与敏感读工具就近 override 声明所需权限点。
- 新增 `PermGate`(纯静态可单测,类比 `Guardrail`):
  ```
  enum Decision { ALLOW, DENY }
  static Decision check(AiTool tool, AgentContext ctx):
      need = tool.requiredPerm()
      if need == null: return ALLOW            // 仅需登录(已登录才能进 agent)
      if RbacService.hasPermission(ctx.perms, need): return ALLOW
      return DENY                               // 缺权硬拒
  ```
- **执行顺序**(`AiAgentService` invoke 前,串行短路):
  1. `Guardrail.gate` == DENY(永久禁区)→ 拒,记审计。
  2. `PermGate.check` == DENY(用户无此功能权限)→ **硬拒绝**,返回明确文案("当前用户(X)无 `need` 权限,无法执行此操作,请联系管理员分配",不进审批、不可被模型/记忆绕过),记审计。
  3. 数据级校验(§3.3)未通过 → 拒。
  4. `Guardrail.gate` == PASS(只读)→ 直接执行;== NEED_APPROVAL(写)→ 进 ApprovalGate(§3.5)。
- **并行批**(R111 单轮多工具)与**子代理委派**入口同样先过 PermGate(子工具集已是只读子集,但仍以子 ctx.perms 校验数据级)。

#### 工具 → 权限点映射(初版,照搬 PermInterceptor 语义)

| 工具(示例) | requiredPerm |
|---|---|
| 读类:gov_overview/health_trend/issue_stats… | `null`(仅登录;模块可见性由前端菜单控制,与 PermInterceptor 普通 GET 一致) |
| 敏感读:table_data/run_analysis/asset_detail(读源库明文)/sched_run_log | 对应模块 view,如 `catalog:view`/`dbsync:view`/`operations:schedule`(见 PermInterceptor SENSITIVE_GET) |
| create_script/create_dev_folder/dev_tree(写) | `develop:edit` |
| create_ods_table(建 Doris 表+落 DnSyncTask) | `develop:edit`(抽数到 ODS 属数据开发) |
| create_sync_job | `dbsync:edit` |
| create_quality_rule | `governance:quality` |
| create_governance_issue | `governance:issue` |
| create_metric | `metrics:edit` |
| create_project/create_project_task | `project:manage` |
| 新增运维类(跑同步/补数/调度) | `dbsync:run`/`operations:backfill`/`operations:schedule` |
| 新增审批类(approve 动作) | `*:approve` 对应模块 |

> 映射维护在工具自身的 `requiredPerm()` 中(就近自描述),与 `PermCatalog.ALL` 保持一致。新增工具(特性 D)必须声明 requiredPerm。

### 3.3 数据级对齐(DataAcl)

新增**显式入参重载**(不读 ThreadLocal),保留原 ThreadLocal 版给 HTTP 控制器复用:

```
DataAclService.canAccessAs(String caller, List<String> roles, Set<String> perms, String resourceType, String resourceId)
DataAclService.deniedIdsAs(String caller, List<String> roles, Set<String> perms, String resourceType)
```
- bypass 条件:开放模式 / caller 匿名 / `*` / `data:all`(与原 `bypass()` 等价,但用显式 perms/roles 判定)。
- 校验异常 fail-closed(返回不可访问)。

**接入点**:
- 结构化资产工具(`asset_detail`/`table_data`/`table_profile`/`lineage_*`/`graph_*`)涉及具体库表 → 以 `resourceType=TABLE`(或现有约定)+ `resourceId=db.table` 调 `canAccessAs`;受限且未授权 → 返回 `not_authorized`(不暴露数据,给出"无该资源数据权限"提示)。
- 列表类工具(如 `sync_dashboard`/`project_overview`/`issue_stats`)→ 用 `deniedIdsAs` 排除受限资源,避免"鬼影"。
- **`run_analysis`(任意只读 SELECT)**:先用 `sql_parse_tables` 解析引用表清单 → 逐表 `canAccessAs`;任一未授权 → 拒(不执行)。脱敏对结果仍 fail-closed。
- 项目维度:复用现有 `project:all-data` 语义——无该权限只能看本人参与项目(沿用 `ProjectService` 既有过滤,agent 工具走同一服务方法即可继承)。

> 说明:DataAcl 是"默认公开"的资源级黑名单,非行级。`run_analysis` 做到"表级授权校验"即与系统其余部分一致;行级/列级遮罩沿用现有脱敏。`resourceType` 取值需与系统现有数据授权 UI 写入的类型对齐(实现期核对 `DataAclController` 实际写入的 type 常量)。

### 3.4 审计

- 每次工具调用在 `dn_ai_step` 记录 actor=`ctx.userName` + 鉴权结果(ALLOW/DENY-perm/DENY-data/NEED_APPROVAL)。沿用现有 `AuditService.recordReturning` fail-closed 流水(写前审计回读未落库则拒执行)。
- DENY 也要留痕(便于业主复盘"谁的 agent 试图越权")。

### 3.5 审批模型(业主拍板:允许用户确认自己 agent)

- **PermGate 决定"能不能做"**(按用户权限);**ApprovalGate 退化为"高危动作二次确认"**。
- 移除 R73 的"禁自批"限制中**针对同一发起人**的部分:**允许同一用户确认自己 agent 的写动作**(本质是用户行使自己已有的权限)。
  - 但审批人仍须**实际拥有该写操作所需权限点**(防越权:A 把会话甩给无权的人确认也不行)——确认动作服务端再校验确认人 perms ⊇ requiredPerm。
  - 永久禁区(§二.2)依旧无审批通道(根本不工具化)。
- HIGH 风险仍每次确认;MEDIUM 沿用会话级一次性豁免规则(R73 收紧后的 session+skill+完全相同 args)。

### 3.6 E 阶段交付物

- `AgentContext` 加字段 + Controller/Child/Cron/Resume 四处填充。
- `AiTool.requiredPerm()` default 方法 + 现有 9 写工具 + 敏感读工具 override。
- `PermGate`(新类,单测覆盖 ALLOW/DENY/`*`/null/异常 fail-closed)。
- `DataAclService.canAccessAs/deniedIdsAs` 重载 + 单测。
- `AiAgentService` invoke 链接入 PermGate + 数据校验(主循环/并行批/子代理/resume 全覆盖)。
- 审批确认放开自批 + 确认人权限校验。
- E2E:viewer 用户(仅 view 权限)→ 调写工具被硬拒;viewer 查未授权表 → not_authorized;有 `develop:edit` 用户 → create_script 走审批→自确认→落库;子代理/cron 路径鉴权同样生效(构造受限数据 + 非超管 owner 验证)。

---

## 四、特性 D — 完整能力覆盖(工具全集)【E 之后】

### 4.1 能力边界公式

**agent 能力 = 系统可执行动作 ∩ 发起用户 RBAC 权限 − 永久基础设施禁区**

- 永久禁区(§二.2,业主拍板始终硬禁):凭据保存/改、deploy、改护栏配置本身、不可逆群毁。
- 其余系统动作全部工具化,逐一标 `requiredPerm` + `risk` + `readOnly`,自动继承 E 的鉴权/审批。

### 4.2 缺口盘点(以 PermInterceptor 写面为系统动作清单的事实来源)

按模块波次补齐(每波 = 实现 + 单测 + 真实库 E2E):

- **W-D1 运维域**:run_sync_job(`dbsync:run`,运行/停止/上下线)、run_datax/run_ods_sync(`develop:run`,跑抽数,长任务异步)、schedule_task 上下线/重跑(`operations:schedule`)、backfill 补数(`operations:backfill`)、baseline 基线(`operations:baseline`)。
- **W-D2 数据源 + 元数据**:datasource CRUD(`datasource:edit`)、metadata/DDL 维护(`catalog:edit`)、subject 主题域。
- **W-D3 治理深化**:数据标准增改(`governance:standard`)、血缘重建(`governance:manage`)、脱敏/分级配置(`governance:manage`)、工单流转(`governance:issue`)、审批(各模块 `*:approve`)。
- **W-D4 MDM + 数据模型**:域/实体/属性/黄金记录(`mdm:manage`)+ 变更审批(`mdm:approve`);数据模型实体/属性/关系建模(`datamodel:edit`)+ 发布审批(`datamodel:approve`)。
- **W-D5 项目深化**:成员/任务/里程碑/公告/Wiki(`project:manage`)+ 发布审批(`project:approve`)。

> 每个新工具都是既有 domain 服务的薄适配器(零侵入)。长任务(跑 DataX/补数)采用"建/触发 + 返回任务号 + 后续异步查状态",不在工具内内联跑满(沿用 R116 经验)。

### 4.3 D 阶段产出

- 工具数从 44 增至覆盖全写面(预计 +15~25 工具,按波次);`AiToolRegistry` 自动收集。
- 工具总数若逼近渐进披露阈值(R102 默认 50),`tool_search` 自动生效(已就位)。
- 每波 E2E 必含:有权用户能做 + 无权用户被 PermGate 硬拒 + 数据级隔离。

---

## 五、特性 A — 数据分析 + 图表可视化

- **`run_analysis`**(只读,`requiredPerm` 按目标库模块 view):入参 `{sql, db?}` 或 `{intent}` 由 LLM 生成 SQL。安全:
  - 单语句强校验:仅允许 `SELECT`/`WITH`;禁 DDL/DML/多语句(用 `SqlExecutor.splitStatements` 校验只 1 条)/`INTO OUTFILE`/注释绕过。
  - 自动包 `LIMIT`(默认 500,上限 2000)、`Statement.setQueryTimeout(30s)`、`setMaxRows`。
  - 默认跑数仓 Doris(`hiveConfig`),按 `db` 路由源库(复用 `AssetDetailService` 连接设施)。
  - **数据级**:§3.3 引用表逐表 `canAccessAs` + 脱敏 fail-closed。
  - 结果走 `previews` 通道(不截断,沿用 R109 `_preview`)。
- **`chart`**(只读):入参 `{type: bar|line|pie|radar, title, data:[{label,value}] | series}`;后端只校验+回 chart spec(`previews` 的 `_chart` 项),前端 `renderTurn` 用 `DN.bars/line/donut/radar` 渲图表卡(SVG 零依赖)。
- **协议纪律**:查数用 run_analysis;要对比/趋势/占比调 chart;数值只能来自工具结果(接现有反幻觉铁律)。
- E2E:"按状态统计同步任务并出柱状图" → run_analysis 取数(受数据权限约束)→ chart 出图;无该库权限用户被拒。

## 六、特性 B — PDF/Word 文档知识库 → RAG

- **依赖**:`pom.xml` 加 Apache PDFBox 2.0.x(Java8/SB2.7 兼容,业主已授权)。docx 零依赖(zip+XML,仿 `XlsxTextExtractor` 新增 `DocxTextExtractor`)。
- **提取**:`AiFileService.readText` 扩 pdf(PDFBox `PDFTextStripper`)/docx(零依赖);限页/字符数防爆。
- **入库 `DocIngestService`**:上传 pdf/docx → 提文本 → 切块(~800 字/块,重叠 100)→ `EmbeddingService.embedBatch` → `VectorStoreClient.upsert` `kind="doc"` payload`{file_id,file_name,chunk_idx,text,owner}`,pointId=`UUID("doc:fileId:chunkIdx")` 幂等;异步(`aiIndexExecutor`);删文件级联删向量点(按 file_id 过滤删)。
  - **数据级**:doc 召回带 owner 作用域(用户只能检索自己上传的文档,沿用 AiFile owner 语义)。
- **检索**:新工具 `doc_search`(`SemanticSearchService.search` kind="doc" + owner 过滤,`requiredPerm=null` 但 owner 隔离);`AiAgentService.buildRagText` 增"# 相关文档"段注入(向量不可用降级跳过)。
- **前端**:文件列表显索引状态(已索引/索引中/失败);`doc_search` 命中可点。
- E2E:上传 PDF → 异步入库 → doc_search 命中正文片段 → agent 基于文档作答(引用来源);他人无法检索到。

## 七、特性 C — SSE 流式响应

- **不动 `chat()` 签名**。新增 `AiAssistService.chatStream(userMessage, context, java.util.function.Consumer<String> onToken)`:请求体加 `"stream":true`,逐行读 `data:` 解析增量 delta(OpenAI `choices[].delta.content` / Anthropic `content_block_delta`)回调 onToken;任何异常回退非流式 `chat()`(降级铁律)。
- **端点**:`GET /api/ai/agent/stream/{sessionId}` 返 Spring 内置 `SseEmitter`(零依赖)。run 异步执行 + per-session 事件汇推送事件:`step`(工具名/序,复用 `writeStep` 锚点)、`token`(终答增量,FINAL 用 chatStream)、`approval`(挂起)、`question`(ask_user)、`done`(含 exitReason)。
- **降级**:1.5s 轮询(R104/R106 现状)**保留为兜底**;SSE 不可用前端自动回退。
- **前端**:send 优先开 SSE,token 增量渲染打字机 + 实时步骤;失败回退轮询。
- E2E:发起对话 → SSE 收到 step 事件序 + 终答 token 流;断网/不支持 → 回退轮询仍可用。

---

## 八、落地顺序与里程碑

**E(地基)→ D(能力,W-D1…W-D5 波次)→ A → B → C**。每个里程碑独立可运行、可 E2E、可回归。

1. **M-E** 权限对齐(功能级 PermGate + 数据级 canAccessAs + 身份全程携带 + 审批放开自批)。
2. **M-D1…M-D5** 按模块补齐工具全集。
3. **M-A** 数据分析 + 图表。
4. **M-B** PDF/Word → RAG。
5. **M-C** SSE 流式。

> 每个里程碑用单独的实施计划(writing-plans)展开;先做 M-E。

## 九、测试与验收(QA 门禁,从严)

- **E**:单测覆盖 PermGate(ALLOW/DENY/`*`/null/fail-closed)、canAccessAs/deniedIdsAs;E2E 用 viewer/有限权限/超管三类用户 × 同步/治理/开发工具,验证功能级硬拒 + 数据级隔离 + 异步路径(子代理/cron)同样生效。
- **D**:每波"有权能做 / 无权被拒 / 数据隔离"三件套 E2E。
- **A**:只读校验拒绝 DDL/DML/多语句;图表卡真机渲染;数据级拒绝越权库。
- **B**:PDF/docx 提取正确;异步入库;owner 隔离;RAG 引用来源。
- **C**:SSE token 流 + 事件序;降级回退轮询。
- 全程:`mvn -o clean package` 绿;真实远程库(<测试服务器>:3307)E2E;前端 Playwright 真机零 console error。

## 十、风险与缓解

- **越权遗漏**:工具→权限点映射若漏标 = 越权风险。缓解:默认 fail-closed(写工具未声明 requiredPerm 视为高危需超管/明确权限,而非放行);加一条对抗审查工作流核对每个工具的 requiredPerm 与其实际副作用是否匹配。
- **run_analysis 数据越权**:SQL 解析若漏表 = 越权。缓解:解析失败 → 拒执行;只允许 SELECT 降低复杂度。
- **异步身份丢失**:已在 §3.1 用 ctx 携带根治;加 E2E 专测异步路径。
- **PDFBox 依赖兼容**:锁 2.0.x(Java8);CI 编译验证。
- **SSE 跨容器/超时**:SseEmitter 设超时 + onTimeout/onError 清理;保留轮询兜底。

---

## 附:与现有记忆/规范的衔接

- 复用 R65 ApprovalGate / R73 安全加固 / R99 对抗审查模式 / R111 并行批 / R116 模块归属经验。
- 永久禁区与身份字段规则与 [[ai-agent-program]] 一致。
- 部署/环境/版本号 bump 规则见交接信息;本阶段先本地连远程库 E2E([[no-deploy-phase]] 现状,部署由业主定)。
