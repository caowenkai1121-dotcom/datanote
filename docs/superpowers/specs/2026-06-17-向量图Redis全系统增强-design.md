# 向量库 / 图库 / Redis 全系统深度应用 — 设计方案(总体方案)

- 日期：2026-06-17
- 状态：已评审通过(业主"按规划全部开始")
- 范围：`com.datanote.platform.ai.vector.*` / `ai.graph.*` / `ai.store.*` / `platform.iam.*` / `domain.governance.*` / `domain.orchestration.*` / `portal.*` / `collab.*` + 前端系统管理页
- 关联:[[server-deploy-datax]](三库已部署 <测试服务器>)、[[ai-agent-perm-program]](向量/图/Redis 基础设施)

---

## 一、背景与目标

三库(Qdrant 向量、Neo4j 图、Redis)已部署并启用,但**应用很浅**:
- **向量**:索引齐全(8 类),但约 90% 用户搜索仍 `LIKE`;无语义查重、无相似资产推荐。
- **图**:仅表级血缘镜像 + 3 个 agent 工具;前端血缘/影响分析仍走 MySQL BFS(全表 O(n) 加载)。
- **Redis**:仅编辑锁;登录限流/权限缓存是进程内 `ConcurrentHashMap`(重启丢、多实例不一致);热读路径(看板/治理总览/首页)每次打 MySQL。

**业主目标**:①把三库深度运用到整个系统、提升系统能力;②管理页可配置三库连接信息。**业主拍板**:范围最大化覆盖;部署形态规划多实例;Redis 配置=存配置+提示重启生效。

---

## 二、架构原则(贯穿所有波次)

1. **降级铁律(最高优先)**:每处增强都被 `vector.available()` / `graph.available()` / Redis try-catch 包裹;DB 不可用 → 自动回退到当前行为(LIKE / MySQL BFS / 直查 / 内存)。**绝不因任一中间件不可用而拖垮主系统**。沿用现有 `VectorStoreClient`/`GraphStoreClient`/`EditLockService` 的 fail-open 范式。
2. **配置 DB 化 + 热生效**:`VectorStoreClient`/`GraphStoreClient` 改为「DB(`dn_system_config`)优先,env 兜底」+ `reloadConfig()` volatile 快照原子发布(完全仿 `EmbeddingService`)。密钥经 `CryptoUtil` AES 加密存。Redis 因 `RedisConnectionFactory` 启动期装配,**存配置 + 测连接,提示"需重启生效"**(不做运行时热重建,避免断现有锁连接)。
3. **多实例正确性**:进程内态(权限缓存 / 登录限流 / 调度去重)迁 Redis,带**内存兜底**(Redis 挂回退内存,单实例不退化)。
4. **复用单元**:新增 `CacheService`(包 `StringRedisTemplate`,JSON 序列化,统一 TTL/失效/fail-open),所有缓存波次复用,单一职责、可单测。
5. **零侵入既有 domain 服务**:增强以「注入只读检索/缓存旁路」方式就近嵌入,不重构业务逻辑;新 mapper 包须进 `@MapperScan`。
6. **身份/安全红线不变**:owner 隔离、脱敏 fail-closed、`settings:config` 权限门、密钥不入日志/不入图谱。

---

## 三、Wave F — 配置地基 + 管理页(先做)

**目标**:三库连接信息可在系统管理页查看/配置/测连接;向量、图热生效,Redis 提示重启。

- **后端配置 DB 化**:
  - `VectorStoreClient`:加 `reloadConfig()`,读 `dn_system_config` 键 `store.vector.enabled/base-url/api-key(加密)/collection`,env 兜底;`@PostConstruct` 调用;探活逻辑复用。
  - `GraphStoreClient`:同款,键 `store.graph.enabled/base-url/user/password(加密)`。
  - Redis:键 `store.redis.host/port/password(加密)`;仅存储 + 读取展示,不热切。
- **新端点**(`StoreController` 扩展,perm `settings:config`):
  - `GET /api/ai/store/db-config`:三库连接配置(密钥脱敏)+ 运行态(ready)。
  - `POST /api/ai/store/db-config`:保存(密钥加密)+ 向量/图 `reloadConfig()` 热生效;Redis 仅存 + 回 `needRestart=true`。
  - `POST /api/ai/store/test`:逐库测连接(向量 GET /collections、图 RETURN 1、Redis PING),返回每库 ok/错误。
- **前端**:系统管理新增「数据存储与中间件」面板(workspace.html,复用 `openStoreConfig` 模式):三库表单(url/key/库名/host/port)+「测连接」按钮(逐库亮灯)+ 健康灯 + Redis 保存后提示重启。`data-perm="settings:config"`。
- **验收**:页面改向量/图 url→保存→`/store/health` 即时变化(热生效);改 Redis→提示重启;测连接三库分别返回真实 ok/失败;密钥存库为密文。

---

## 四、向量轨(语义化整个目录)

- **V1 目录语义搜索**:`DataMapService.searchTables`/`aiSearch`、`MetricController` 列表、`DatasetService.list` → 向量召回排序 + `LIKE` 兜底(向量不可用回退);前端搜索框直接语义化(改 `MetadataController` 搜索委托)。引用现有 `SemanticSearchService`。
- **V2 相似资产推荐**:`AssetDetailService` 详情响应加 `similarTables`/`similarColumns`(向量召回 top-N,过滤自身);前端资产详情「相似资产」区。
- **V3 语义查重 + 标准匹配**:建/改 指标(`MetricController.save`)、术语、数据标准前,向量查相似(>阈值)→ 返回「疑似重复」告警(不阻断,提示复用);列 → 数据标准语义匹配建议(`StandardService`)。
- **V4 Agent 上下文增强**:`PromptBuilder`/`AiAgentService` 首轮注入向量召回 top-K 术语/标准/相似表(精简,降 token);可选查询扩展。

每子波加索引保障:`MetadataCrawlerService` 采集后已触发 `fullReindex`;补 V3/V2 涉及的 kind 增量索引钩子(建指标/术语/标准后 upsert 单点,不必全量)。

---

## 五、图谱轨(真血缘能力)

- **G1 血缘前端/REST 走图**:血缘视图后端端点(`LineageController` / orchestration)trace/impact/neighbors → 优先 Neo4j 多跳(返回路径链),MySQL BFS 兜底(现仅 agent 工具用图,UI 仍 MySQL)。统一经一个 `LineageQueryService`(图优先+兜底)收口,消除重复。
- **G2 新图能力**:环检测(`MATCH (t)-[:FLOWS_TO*]->(t)`)、最短路径(根因)、影响面/爆炸半径计数,在血缘 UI + 新端点暴露。
- **G3 列级血缘入图**:`GraphMirrorService` 扩 `:Column` 节点 + `:DERIVES_FROM` 关系(从 `dn_lineage_edge` 列级边镜像);敏感分级沿血缘传播查询(下游列继承上游 SENSITIVE/PII 提示)。
- **G4 图赋能治理**:`HealthScoreService.lineageScore` 血缘覆盖率改 Cypher 计数(去全表内存加载);删表/下线(`LifecycleService`)级联下游告警(图查下游非空则警示/接通知)。

---

## 六、Redis 轨(缓存 + 多实例正确性)

- **R1 多实例正确性(去内存化,内存兜底)**:
  - `LoginAttemptService`:计数/锁定迁 Redis(`login:attempt:{u}` TTL10m / `login:locked:{u}` TTL15m)。
  - `PermInterceptor` 权限缓存 + `RbacService.getUserPerms`:迁 Redis(`perm:user:{username}` TTL30s),`evictAll`→ Redis 删键(改密/改角色即时跨实例失效)。
  - 任务调度去重(`TaskSchedulerService.runningTasks`)/cron 领取:Redis SETNX 分布式锁(跨实例防重复执行)。
- **R2 热读缓存(`CacheService` 旁路 + 写时失效)**:
  - 看板 `DashboardController.stats/metrics`(TTL 5m)。
  - 治理总览 `OverviewService.overview`(TTL 10m,治理写时失效)。
  - 首页工作台 `ProjectHomeService.home`(per-user TTL 30s)。
  - 质量分析 `QualityService.failureAnalysis`(TTL 5m)。
  - 全部 fail-open:Redis 挂 → 直查 DB。

---

## 七、测试与验收(每波,从严)

- 单测:纯逻辑(CacheService key/TTL/序列化、图查询构造、向量排序合并、降级分支)。
- `mvn -o clean package` 绿(基线 654/2 预存 UI 失败,不新增)。
- **服务器活体 E2E**(连真 Qdrant/Neo4j/Redis):每波核心路径 + **降级验证**(停某库 → 确认自动回退、系统不崩)。
- 多实例正确性波(R1):构造两进程(或模拟)验证跨实例一致(权限改后即时失效 / 锁互斥)。
- 按波次提交 + 推送;每波文档进 `docs/iterations/`。

---

## 八、波次路线图(约 11 波,7 角色流程逐波)

| 波 | 内容 | 关键交付 |
|---|---|---|
| **F** | 配置地基+管理页 | 三库 DB 配置+热生效(Redis 重启提示)+测连接+前端面板 |
| **V1** | 目录语义搜索 | searchTables/指标/数据集 向量排序+兜底 |
| **V2** | 相似资产推荐 | 资产详情 similarTables/Columns |
| **V3** | 语义查重+标准匹配 | 建指标/术语/标准查重告警 |
| **V4** | Agent 上下文增强 | prompt 向量注入 |
| **G1** | 血缘前端走图 | LineageQueryService 图优先+兜底 |
| **G2** | 图新能力 | 环检测/最短路径/爆炸半径 |
| **G3** | 列级血缘入图 | :Column/:DERIVES_FROM+敏感传播 |
| **G4** | 图赋能治理 | 健康分 Cypher/删表级联告警 |
| **R1** | Redis 多实例正确性 | 登录限流/权限缓存/调度锁去内存化 |
| **R2** | Redis 热读缓存 | CacheService+看板/治理/首页/质量缓存 |

> 每波独立可运行、可 E2E、可回归;降级铁律贯穿。F 为地基先做,V/G/R 三轨随后推进。

---

## 九、风险与缓解

- **中间件不可用拖垮系统** → 降级铁律 + 每波降级 E2E 专测。
- **Redis 改连接断现有锁** → 不做热重建,仅存配置+重启提示(业主已拍板)。
- **缓存脏读** → 写时失效 + 短 TTL;权限缓存失效即时(改密/角色立即删键)。
- **图镜像与 MySQL 不一致** → MySQL 为唯一事实源(SoT),图为派生镜像,查询图优先但兜底 MySQL;镜像失败仅告警不阻断。
- **向量索引滞后** → 增量索引钩子 + 采集后全量;召回不可用回退 LIKE。
- **多实例 cron 重复** → Redis SETNX + 现有 DB 原子占行双保险。
