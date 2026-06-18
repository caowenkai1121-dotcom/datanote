# DataNote Architecture（Doris 版）

DataNote 是一个 Spring Boot 单体应用，前端为静态单页工作台，元数据存储在 MySQL，数仓执行引擎为 Apache Doris。

## Runtime

| 组件 | 用途 |
|---|---|
| Spring Boot | API、调度、SQL 执行、DataX 任务生成 |
| MySQL | DataNote 元数据库 |
| Apache Doris | ODS 建表、SQL 开发、数据地图查询 |
| DataX | 可选；从 MySQL 源库同步到 Doris |
| DolphinScheduler | 可选；上线调度脚本和同步任务 |

Doris 连接由环境变量或系统配置显式提供：

```yaml
doris:
  host: ${DORIS_HOST}
  query-port: 9030
  database: ods
  username: root
  password: ${DORIS_PASSWORD}
```

## Core Flow

1. 元数据接口读取源 MySQL 的库、表、字段。
2. `HiveService`（保留类名兼容旧代码）生成 Doris `ENGINE=OLAP` 建表语句并通过 MySQL JDBC 执行。
3. `DataxService` 生成 `mysqlreader -> mysqlwriter` DataX JSON，目标 JDBC 为已配置的 Doris FE `${DORIS_HOST}:9030/ods`。
4. SQL 工作台通过 `/api/doris/execute` 执行 Doris SQL；旧 `/api/hive/*` 路径保留兼容。

## Main Modules

| 模块 | 说明 |
|---|---|
| `HiveConfig` | Doris JDBC/HikariCP 配置，兼容旧配置键 |
| `HiveService` | Doris DDL 生成、SQL 执行、结果集返回 |
| `DataxService` | DataX JSON 生成和任务执行 |
| `SystemConfigController` | Doris 连接测试、保存和热加载 |
| `DataMapService` | Doris 库表元数据、预览、探查 |

## 真实分层架构(P2-03 更新)

代码已演进为分层多子域,非单一 Hive/DataX 模块:

- **`com.datanote.domain.*` — 业务子域**:`metadata`(数据地图/资产)、`integration`(数据同步/DataX/CDC)、`orchestration`(调度/血缘)、`governance`(质量/标准/分级/脱敏/健康分/治理总览)、`consumption`(指标/数据集)、`datamodel`(数据建模)、`mdm`(主数据)、`develop`(脚本开发/审批)、`project`(项目空间)、`datasource`(数据源/探查)。
- **`com.datanote.platform.*` — 横切能力**:`iam`(RBAC/数据ACL/登录限流/CSRF)、`ai`(Agent 天工司辰 + `vector` Qdrant 语义检索 + `graph` Neo4j 血缘 + `store` 三库配置)、`audit`(操作审计)、`cache`(Redis 旁路缓存)、`config`(Security/WebSocket/系统配置)、`portal`(看板/首页)。
- **`src/main/resources/static/js/*` — 前端模块**:`workspace.html` 为壳层(路由/权限守卫/WS 订阅/API 薄封装),各模块 JS(`gov-*`/`project.js`/`datamodel.js`/`ai-agent.js`/`dn-common.js` 等)负责 render/init。

### 边界与事实来源
- **兼容层**:旧 `/api/datax/*`、`/api/hive/*`、`/api/doris/*` DDL 入口保留兼容,已统一接入数据源访问校验(`DataAclService`)与破坏性 SQL 拦截(`sql:danger`);新同步域走 `SyncJobService`。
- **设计系统唯一事实来源**:`docs/设计规范.md`(Slate & Indigo);前端优先用 `dn-common.js` 的 `DN.*` 组件工厂,禁硬编码色值/魔法 px。
- **中间件定位**:向量(语义检索,ACL 过滤)、图(血缘/影响,增量镜像)、Redis(缓存/锁/限流,不承担强一致);三者均 fail-open 降级,不可用时回退 LIKE/MySQL/直查/内存。
