# DataNote 关系型数据库同步功能 — 设计文档

- 日期：2026-05-26
- 状态：设计待评审
- 目标项目：DataNote（Java 8 + Spring Boot 2.7.18，单模块，单 JAR）
- 借鉴来源：`D:\data\zg\etl\etl-sync-system`（Java 17 + Spring Boot 3.2.5，多模块，DeepSeek 实现）

---

## 一、背景与目标

DataNote 现有的"数据同步"只支持 **MySQL → Hive**（调用外部 DataX 引擎），且增量同步是假的（仅靠表名后缀 `_df`/`_di` 区分，无 WHERE 条件、无断点）。

旧项目 `etl-sync-system` 实现了一套较完整的**关系型数据库之间**的同步（全量/增量/CDC），但工程粗糙、依赖很重（Kafka、Debezium-Connect、Druid、Hutool、MapStruct、Guava、11 种数据库驱动），且基于 Java 17。

**本次目标**：把旧项目最值钱的"纯 JDBC 进程内同步引擎"逻辑提炼出来，优化后集成进 DataNote，为其补齐**关系型数据库互通**能力，遵循"最小组件"原则——所有服务都部署在服务器 38.76.183.50 上。

### 已确认的范围决策

| 决策项 | 结论 |
|--------|------|
| 核心场景 | 关系库之间互通（MySQL ↔ MySQL / Doris / StarRocks），这是 DataNote 完全缺失、旧项目最擅长的能力 |
| 同步模式 | 全量 + 增量 + CDC 实时，三种都要 |
| CDC 实现 | **Debezium 嵌入式引擎**（进程内读 binlog，零 Kafka、零独立服务），Java 8 锁定 Debezium 1.9.x |
| 第一版数据源 | MySQL 协议族（MySQL / Doris / StarRocks），一个 `mysql-connector-j` 驱动全搞定 |
| 集成形态 | 旧项目逻辑提炼改写进 DataNote **单模块** `com.datanote.sync` 包，仅新增 2 个 debezium jar |
| Java 版本 | 保持 **Java 8**（老的 hive-jdbc 3.1.2 / hadoop 3.1.3 不宜升级 JDK），旧项目 Java 17 语法需降级改写 |

---

## 二、关键约束与现状（调研结论）

### DataNote 可直接复用的基础设施

1. **自研本地调度引擎**（`TaskSchedulerService`）：`@EnableScheduling` + `tick()` 每 15 秒轮询 `dn_scheduler_run` 表中 `status=WAITING` 的任务，检查依赖 + cron 时间后提交线程池执行（并发上限 4）。**完全不依赖 DolphinScheduler**（后者仅为可选上线功能）。`task_type` 现支持 `script`/`syncTask`。
2. **WebSocket 实时推送**（`LogBroadcastService`，Spring STOMP + SockJS）：推 `/topic/task-log`（日志）、`/topic/task-status`（状态），前端已订阅。
3. **统一执行记录表** `dn_task_execution`：含 `read_count`/`write_count`/`error_count`/`duration`/`log`/`status`，正好做同步统计。
4. **密码加密** `CryptoUtil`（AES/CBC/PKCS5Padding）：`encrypt`/`decrypt`/`decryptSafe`，密钥来自 `datanote.crypto.key` 环境变量。
5. **数据源表** `dn_datasource`：`type` 字段 VARCHAR(20) 自由值，现支持 `MySQL/Hive/PostgreSQL/Oracle`。
6. **HikariCP**：随 `mybatis-plus-boot-starter → spring-boot-starter-jdbc` 已在 classpath，可直接做连接池，零新增依赖。

### 当前的不足（本次会顺手修掉）

- 外部数据源连接是 `DriverManager` 每次新建、URL 硬编码 `jdbc:mysql://`、无连接池。

### 旧项目可复用的逻辑

- 全量游标分页抽取（`FullTableExtractor`，O(1) 内存）。
- 增量策略模式（`TimestampIncrementalStrategy` / `AutoIncrementalStrategy`）+ 断点续传。
- CDC 的 `INSERT/UPDATE/DELETE` 写入逻辑（`CdcSyncEngine.java:1291-1440`，含 MySQL `ON DUPLICATE KEY`、PG `ON CONFLICT`、Doris `INSERT` 分支，已经很完整）。
- 多方言类型映射、自动建表思路。

### 旧项目的粗糙之处（本次必须修复）

| 问题 | 处理 |
|------|------|
| AES 密钥硬编码在源码（`EncryptionUtil.DEFAULT_KEY`） | 复用 DataNote `CryptoUtil`，密钥走环境变量 |
| SQL 字符串拼接表名/字段（注入风险） | 标识符白名单正则校验 + 值全部 `PreparedStatement` 参数化 |
| 解密失败静默回退明文 | 用 `decryptSafe`，仅兼容历史明文，记录告警 |
| 每次新建 JDBC 连接 | HikariCP 池化复用 |
| CDC 依赖 Kafka + 外部 Debezium Connect | 改为 Debezium 嵌入式，零外部服务 |
| 11 种库 + 重依赖 | 砍到 MySQL 协议族 + JDK 自带能力 |
| 增量断点更新时机不严谨 | 仅成功批次后推进断点，配合幂等 upsert 实现 at-least-once |

---

## 三、整体架构

```
前端 workspace.html（新增独立视图 view-dbsync「关系库同步」）
      │  REST + WebSocket(STOMP，已有)
      ▼
SyncJobController ──► SyncJobService（任务 CRUD / 手动运行 / 启停 CDC）
      │
      ├─ 全量/增量（定时）：复用 dn_scheduler_run + TaskSchedulerService.tick()（task_type='syncJob' 新分支）
      │                       └─► SyncJobExecutor
      └─ CDC（常驻）：CdcEngineManager（@PostConstruct 拉起 RUNNING 任务，优雅停机）
      ▼
SyncEngineFactory ──► FullSyncEngine │ IncrementalSyncEngine │ CdcSyncEngine(Debezium 嵌入式)
      ▼
DbConnector ⇐ MysqlConnector（元数据 / 游标读 / 批量写 / 标识符校验）
      ▼
ConnectionManager（HikariCP 池，按 dsId 缓存）  ⇐ dn_datasource + CryptoUtil
      ▼
进度/日志 ──► dn_task_execution（read/write/error 计数）+ LogBroadcastService（WebSocket 实时）
```

包结构（DataNote 单模块内新增）：

```
com.datanote.sync
├── controller
│   └── SyncJobController            REST 接口
├── service
│   ├── SyncJobService               任务 CRUD、手动运行、CDC 启停
│   ├── SyncJobExecutor              全量/增量执行入口（被调度/手动调用）
│   └── CdcEngineManager             CDC 常驻引擎生命周期管理
├── engine
│   ├── SyncEngine                   统一接口 sync(ctx)
│   ├── SyncEngineFactory            按 sync_mode 选择引擎
│   ├── full/FullSyncEngine
│   ├── incremental/IncrementalSyncEngine
│   ├── incremental/IncrementalStrategy（+ Timestamp / AutoIncrement 两实现）
│   └── cdc/CdcSyncEngine            Debezium 嵌入式
├── connector
│   ├── DbConnector                  接口
│   ├── MysqlConnector               唯一实现（MySQL/Doris/StarRocks）
│   └── ConnectionManager            HikariCP 池缓存
├── cdc
│   ├── JdbcOffsetBackingStore       Debezium offset 存 dn_cdc_offset
│   └── JdbcSchemaHistory            Debezium schema history 存 dn_cdc_schema_history
├── schema
│   ├── TypeMappingService           MySQL 类型 → 目标类型
│   └── TableSchemaService           自动建表 DDL 生成
├── model（entity）
│   ├── DnSyncJob
│   ├── DnCdcOffset
│   └── DnCdcSchemaHistory
├── mapper
│   ├── DnSyncJobMapper
│   ├── DnCdcOffsetMapper
│   └── DnCdcSchemaHistoryMapper
└── dto
    └── SyncContext / TableSyncConfig / FieldMapping 等
```

---

## 四、数据模型

### 4.1 复用现有表（零结构改动）

| 表 | 复用方式 |
|----|---------|
| `dn_datasource` | `type` 新增允许值 `Doris`/`StarRocks`（应用层枚举，无 DDL 改动） |
| `dn_task_execution` | `task_type` 用新值 `DbSync`；`sync_task_id` 存 `dn_sync_job.id`（与 `task_type` 配合区分来源） |
| `dn_scheduler_run` | `task_type` 新增 `syncJob`，全量/增量任务走每日调度 |
| `dn_task_dependency` | 第一版关系库同步不接入依赖（可后续扩展） |

### 4.2 新增 3 张表

```sql
-- 关系库同步任务定义
CREATE TABLE IF NOT EXISTS dn_sync_job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    job_name        VARCHAR(200) NOT NULL COMMENT '任务名称',
    source_ds_id    BIGINT       NOT NULL COMMENT '源数据源ID（dn_datasource）',
    target_ds_id    BIGINT       NOT NULL COMMENT '目标数据源ID（dn_datasource）',
    source_db       VARCHAR(100) DEFAULT NULL COMMENT '源库（缺省用数据源默认库）',
    target_db       VARCHAR(100) DEFAULT NULL COMMENT '目标库',
    sync_mode       VARCHAR(20)  NOT NULL COMMENT 'FULL / INCREMENTAL / CDC',
    table_config    LONGTEXT     COMMENT 'JSON: [{sourceTable,targetTable,createTargetTable,incrementalField,incrementalType,incrementalValue}]',
    field_mapping   LONGTEXT     COMMENT 'JSON 字段映射(可选): [{sourceTable,maps:[{source,target}]}]',
    write_mode      VARCHAR(20)  DEFAULT 'UPSERT' COMMENT 'UPSERT / INSERT / INSERT_IGNORE',
    batch_size      INT          DEFAULT 1000 COMMENT '批量大小',
    schedule_cron   VARCHAR(64)  DEFAULT NULL COMMENT 'Cron(全量/增量用; CDC 不用)',
    schedule_status VARCHAR(16)  DEFAULT 'offline' COMMENT 'online / offline',
    status          VARCHAR(20)  DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/STOPPED/PAUSED/FAILED',
    retry_times     INT          DEFAULT 1 COMMENT '失败重试次数',
    timeout_seconds INT          DEFAULT 0 COMMENT '超时(秒)，0=不限',
    created_by      VARCHAR(50)  DEFAULT '',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_sync_mode (sync_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系库同步任务';

-- CDC binlog 位点（Debezium offset 持久化，断点续传）
CREATE TABLE IF NOT EXISTS dn_cdc_offset (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    job_id       BIGINT       NOT NULL COMMENT '关联 dn_sync_job.id',
    offset_key   VARCHAR(512) NOT NULL COMMENT 'Debezium offset key',
    offset_value LONGTEXT     COMMENT 'Debezium offset value(binlog file+pos/gtid)',
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_key (job_id, offset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC binlog 位点';

-- CDC 表结构变更历史（Debezium schema history，解析 binlog DDL 必需）
CREATE TABLE IF NOT EXISTS dn_cdc_schema_history (
    id           BIGINT     NOT NULL AUTO_INCREMENT,
    job_id       BIGINT     NOT NULL COMMENT '关联 dn_sync_job.id',
    history_data LONGTEXT   COMMENT 'Debezium schema history 一条记录(JSON)',
    created_at   DATETIME   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC 表结构变更历史';
```

### 4.3 增量断点存储

单表/多表统一把每张表的 `incrementalValue`（断点）存在 `dn_sync_job.table_config` JSON 的对应元素里，**每个成功批次后回写**。首次无断点：按配置初始值，或取源表 `MIN(field)`，或先做一次全量再转增量。

---

## 五、连接器与连接管理

### 5.1 ConnectionManager

- `Map<Long dsId, HikariDataSource>` 懒加载缓存；首次用某数据源时建池，后续复用。
- 建池时：查 `dn_datasource` → `CryptoUtil.decryptSafe` 解密密码 → 拼 MySQL 协议 jdbcUrl（含 `dn_datasource.extra_params`）→ 建 HikariDataSource（小池，如 max 5）。
- 数据源被编辑/删除时失效并关闭对应池。
- CDC 引擎对源库的 binlog 连接由 Debezium 自己管理，**不走** ConnectionManager；写目标库走 ConnectionManager。

### 5.2 DbConnector / MysqlConnector

接口（保留以备将来扩展 PG，第一版只一个实现）：

```
interface DbConnector {
    String getDatabaseType();                 // MYSQL / DORIS / STARROCKS
    Connection getConnection();               // 来自 ConnectionManager 的池
    TableInfo getTableInfo(String table);     // 列(名/类型/可空/注释) + 主键
    List<String> listTables(String db);
    long getRowCount(String table);
    String quoteIdentifier(String id);        // 反引号 + 白名单校验
}
```

标识符安全：`quoteIdentifier` 先用正则 `^[A-Za-z0-9_$]+$` 白名单校验，不合法直接拒绝，再加反引号。彻底消除旧项目的拼接注入风险。

---

## 六、三种同步引擎

统一接口：

```
interface SyncEngine { void sync(SyncContext ctx) throws Exception; boolean isRunning(); void stop(); }
```

`SyncContext` 含：jobId、executionId、源/目标 connector、表配置、字段映射、batchSize、writeMode、计数器（read/write/error）、停止标志、日志回调。

### 6.1 FullSyncEngine（全量）

- 逐表处理；若 `createTargetTable` 且目标表不存在 → `TableSchemaService` 建表。
- 读取：**keyset 游标分页**——`SELECT cols FROM src WHERE pk > ? ORDER BY pk LIMIT batchSize`，以上一批最大主键为游标。无主键表回退 MySQL 流式读取（`TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `setFetchSize(Integer.MIN_VALUE)`）。
- 写入：按目标类型选择写入 SQL（见 6.4），`addBatch` 到 `batchSize` 后 `executeBatch` + commit。
- 全量语义：写入前可选 `TRUNCATE` 目标表（全量覆盖），或纯 upsert（幂等合并）。
- 内存 O(1)，累计 read/write count，定期推 WebSocket 进度。

### 6.2 IncrementalSyncEngine（增量）

- 策略接口：

```
interface IncrementalStrategy {
    String type();                                  // TIMESTAMP / AUTO_INCREMENT
    String buildWhere(String field);                // "field > ?"，值用参数绑定
    Object readPosition(ResultSet rs, String field);// 取本行增量值
    int compare(Object a, Object b);                // 比较，求批次最大值
}
```

- 流程：每表读 `incrementalField`/`incrementalType`/`incrementalValue` → `SELECT ... WHERE field > ? ORDER BY field`（值参数化）→ keyset/游标分页读 → 批量 upsert 写 → 跟踪本批最大增量值 → **成功提交后**把最大值回写 `table_config.incrementalValue`。
- 首次无断点：按配置初始值 / 源表 `MIN(field)` / 先全量。

### 6.3 CdcSyncEngine（CDC，Debezium 嵌入式）

- 用 `io.debezium.engine.DebeziumEngine`（1.9.x），connector = `io.debezium.connector.mysql.MySqlConnector`。
- 关键配置（Debezium 1.9 配置 key）：
  - `connector.class = io.debezium.connector.mysql.MySqlConnector`
  - `database.hostname/port/user/password`（源库，从 dn_datasource 解密获取）
  - `database.server.id`（唯一，建议用 jobId 派生）
  - `database.server.name`（topic 前缀，用 `datanote_cdc_<jobId>`）
  - `database.include.list` / `table.include.list`（按 table_config 过滤）
  - `snapshot.mode = initial`（首启全量 snapshot + 之后增量；已有位点则自动续传）
  - `offset.storage = com.datanote.sync.cdc.JdbcOffsetBackingStore`
  - `database.history = com.datanote.sync.cdc.JdbcSchemaHistory`
  - 透传 `job_id` 给两个自定义存储类（用于按任务隔离）。
- 消费：`DebeziumEngine.ChangeConsumer` 收到 `SourceRecord` → 从 Struct 解析 `op`(c/r/u/d)、`before`、`after`、主键 → 转成统一变更事件 → **复用旧项目的 `executeInsert/executeUpdate/executeDelete` 写入逻辑**（按目标类型生成幂等 SQL）。
- **改写点**：旧项目从 Kafka JSON 解析（`parseDebeziumMessage`），本次改成从 Debezium `SourceRecord`/`Struct` 解析；写入层直接复用。
- 自定义存储：
  - `JdbcOffsetBackingStore`：实现/继承 Kafka Connect `OffsetBackingStore`（可继承 `MemoryOffsetBackingStore` 重写 `save()`/`load()`），持久化到 `dn_cdc_offset`。
  - `JdbcSchemaHistory`：继承 Debezium `AbstractDatabaseHistory`，`storeRecord`/`recoverRecords` 读写 `dn_cdc_schema_history`（参考内置 `FileDatabaseHistory`）。

### 6.4 写入策略（按目标库类型）

| 目标类型 | 写入 SQL |
|----------|---------|
| MySQL | UPSERT → `INSERT ... ON DUPLICATE KEY UPDATE`；或 `INSERT IGNORE`；或 `INSERT` |
| Doris / StarRocks | `INSERT INTO`（Unique Key 模型按主键自动 upsert）。第一版用 JDBC 批量 INSERT，后续可优化为 Stream Load |
| DELETE（CDC） | `DELETE FROM t WHERE pk = ?`（Doris 需表为 Unique 模型且开启 batch delete） |

### 6.5 类型映射与自动建表（TableSchemaService）

- **MySQL → MySQL**：列定义近乎照搬源表（类型、长度、可空、注释），生成等价 `CREATE TABLE`。
- **MySQL → Doris/StarRocks**：类型映射（`tinyint/int/bigint`→同名、`varchar/char`→`varchar`、`text/longtext`→`string`、`datetime/timestamp`→`datetime`、`decimal(p,s)`→`decimal(p,s)`、`double/float`→同名），生成 Unique Key 模型：`UNIQUE KEY(pk) ... DISTRIBUTED BY HASH(pk) BUCKETS n`。
- 建表是可选项（`createTargetTable`）；目标已存在则跳过。

### 6.6 进度、日志与错误处理

- `dn_task_execution`：执行开始建 `RUNNING` 记录 → 过程更新 `read_count`/`write_count` → 结束写 `status`/`duration`/`error_count`/`log`（截断保护）。
- `LogBroadcastService`：推 `/topic/task-log`（日志行）+ `/topic/task-status`（状态/进度）。前端实时展示。
- 错误：单表失败不中断其余表（累计 `error_count`），整体状态反映是否有失败；增量断点仅在批次成功后推进，配合幂等 upsert 保证 at-least-once。

---

## 七、调度与生命周期

### 7.1 全量 / 增量（复用每日调度）

- `dn_sync_job.schedule_status = online` 时纳入调度。
- `TaskSchedulerService.startDailyRun()` 为这些任务建 `dn_scheduler_run(task_type='syncJob')` 记录；`tick()` 轮询到后检查 cron 时间 → 提交线程池。
- 在 `TaskSchedulerService` / `TaskExecutionService` 加 `task_type='syncJob'` 分支，调用 `SyncJobExecutor.run(jobId, runDate)`。
- 手动运行：`SyncJobController` 直接调 `SyncJobExecutor`，不经 `dn_scheduler_run`，仅记 `dn_task_execution`。

### 7.2 CDC（常驻引擎）

- `CdcEngineManager`：
  - `@PostConstruct`：查所有 `sync_mode=CDC && status=RUNNING` 的任务，逐个拉起 Debezium 引擎（应用重启自动恢复）。
  - `start(jobId)` / `stop(jobId)`：前端启停；启动后置 `status=RUNNING`，停止置 `STOPPED`。
  - 每个 CDC 任务一个 `DebeziumEngine` + 单线程 executor。
  - `@PreDestroy`：优雅停止所有引擎，提交位点。

---

## 八、前端（workspace.html 单文件 SPA）

新建独立视图 **`view-dbsync`「关系库同步」**，与现有 Hive 同步并列，复用现有数据源下拉、库表加载、字段映射表格等 JS 组件。

- 源/目标**双数据源 + 双库**选择
- 同步模式：全量 / 增量 / CDC
- 多表勾选 + 源表→目标表映射；增量字段配置；写入模式；是否自动建表
- 调度 cron（全量/增量）或启动/停止开关（CDC）
- 任务列表 + 执行历史 + 实时日志面板（订阅现有 `/topic/task-log`）

REST 接口（`SyncJobController`，前缀 `/api/sync-job`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 任务列表 |
| GET | `/{id}` | 任务详情 |
| POST | `/save` | 新增/编辑 |
| DELETE | `/{id}` | 删除 |
| POST | `/{id}/run` | 手动运行（全量/增量） |
| POST | `/{id}/start` | 启动 CDC |
| POST | `/{id}/stop` | 停止 CDC |
| GET | `/{id}/executions` | 执行历史 |
| GET | `/preview-columns` | 预览源表字段（建映射用） |

---

## 九、组件清单（"最小组件"落地）

| 类别 | 内容 |
|------|------|
| **新增 jar（仅 2 个）** | `io.debezium:debezium-embedded:1.9.7.Final`、`io.debezium:debezium-connector-mysql:1.9.7.Final`（含传递的 `debezium-api`、`debezium-core`、`kafka-connect` 相关库，但**不运行 Kafka broker**） |
| **复用、零新增** | HikariCP、`mysql-connector-j`、Spring WebSocket/STOMP、自研调度、`dn_task_execution`、`dn_datasource`、`CryptoUtil`、FastJSON2 |
| **明确不引入** | Quartz、Kafka broker、Debezium Connect、Druid、Hutool、MapStruct、Guava、PostgreSQL/Oracle/SQLServer/ClickHouse 等驱动、DataX（关系库同步不走 DataX） |

新增配置项（`application.yml`，全部有默认值）：

```yaml
datanote:
  sync:
    pool-max-size: ${SYNC_POOL_MAX:5}          # 每个数据源 HikariCP 池上限
    default-batch-size: ${SYNC_BATCH:1000}
    cdc-server-id-base: ${CDC_SERVER_ID_BASE:6000}  # Debezium server.id 基数
```

---

## 十、服务器部署（38.76.183.50，全部在服务器上）

- 集成后**仍是单 JAR**，仅多 2 个 debezium 依赖；**不需要 Kafka / Debezium Connect / DataX**。
- 部署流程：本地编译打包 → 替换服务器上的 JAR → 在服务器 MySQL 执行 3 张新表 DDL → 重启服务。
- **实施第一步**：SSH 登录服务器核实现状（当前启动方式/进程管理、MySQL 版本、是否已有 binlog 配置、`/opt` 下目录结构）。
- **CDC 前置条件**（写入部署文档）：源 MySQL 需
  - `log_bin = ON`、`binlog_format = ROW`、`binlog_row_image = FULL`
  - 唯一 `server_id`（与 Debezium 的 `database.server.id` 不冲突）
  - 同步账号权限：`SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT`

---

## 十一、实施顺序（每步可独立验证）

1. 加依赖（debezium 1.9.7）+ 3 张表 DDL（新增 `sql/23_db_sync_tables.sql`）+ 配置项。
2. `DbConnector` / `MysqlConnector` + `ConnectionManager`（HikariCP）+ 标识符校验。
3. **FullSyncEngine**：先打通 MySQL→MySQL 全量（最小闭环，先不建表、目标表手工建）。
4. **IncrementalSyncEngine**：时间戳 / 自增 ID 策略 + 断点。
5. `TypeMappingService` + `TableSchemaService`：自动建表 + Doris/StarRocks 目标支持。
6. **CdcSyncEngine**：Debezium 嵌入式 + `JdbcOffsetBackingStore` + `JdbcSchemaHistory` + `CdcEngineManager`。
7. 调度接入：`TaskSchedulerService` 加 `syncJob` 分支 + `SyncJobExecutor`；打通 `dn_task_execution` 与 WebSocket。
8. 前端 `view-dbsync`。
9. 服务器部署 + 端到端联调。

---

## 十二、验收标准（第一版"完成"）

- MySQL→MySQL 与 MySQL→Doris 的**全量、增量、CDC** 各跑通一个真实任务。
- CDC 能捕获 INSERT/UPDATE/DELETE，并在应用重启后从断点续传。
- 进度 / 日志在前端实时可见。
- 全程零 Kafka、零 DataX、单 JAR 部署在服务器。

---

## 十三、风险与待验证点

1. **Debezium 1.9.7 在 Java 8 + Spring Boot 2.7 的兼容性**：实施第 1 步先做一个最小验证（能启动嵌入式引擎、能读到一条 binlog 事件），确认 `kafka-connect` 传递依赖与 DataNote 现有依赖无冲突。若 1.9.7 有问题，回退验证 1.9.6 / 1.8.x。
2. **fat jar 下 Debezium 的类加载**（`Class.forName` 加载 connector/converter）：旧项目曾在 Kafka 消费者处理过线程上下文 ClassLoader 问题；嵌入式模式同样需要关注，必要时设置线程上下文 ClassLoader。
3. **Doris 的 DELETE / UPDATE 语义**：依赖 Unique Key 模型，建表时须正确指定主键与模型；CDC 的 DELETE 需要 Doris 开启相关支持。
4. **依赖体积**：debezium-connector-mysql 会引入若干传递依赖，需在打包后核对 jar 体积与潜在冲突（排除不必要的传递依赖）。
```
