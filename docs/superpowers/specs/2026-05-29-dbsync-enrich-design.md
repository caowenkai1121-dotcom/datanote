# DataNote 关系库同步 — 功能丰富设计文档（2026-05-29）

- 状态：设计待评审
- 目标项目：DataNote（Java 8 + Spring Boot 2.7.18，单模块单 JAR）
- 来源：两轮 workflow 调研——`wf_4563ab1a-26d`（10 agent 现状+竞品调研）、`wf_e729fcd7-ef9`（7 agent 分批详设+跨切面对账）
- 全量微观详设（每功能 approach/行号/files/tests）见 workflow 输出：
  `C:\Users\caowe\AppData\Local\Temp\claude\D--data-datanote\f9b16efb-df04-41c8-9f3e-74e266685a04\tasks\w59ppq0s0.output`

---

## 一、背景与目标

关系库同步第一版（全量 keyset 分页 / 增量复合游标 / CDC Debezium 嵌入式）功能正确，但"简单"集中在四点：

1. 管道只做"读列→原样写列"，无任何值级加工（无行过滤、无转换、无脱敏、无默认值、无前后置 SQL）。
2. 健壮性薄弱：失败即终止、无重试退避、无脏数据阈值、全速拉取无限流、无对账。
3. 只支持单列主键、单 MySQL 源、单方言（分页/类型/DDL/写 SQL 散落多处硬编码）。
4. 可运维性近乎空白：无监控大盘、无告警外发、无断点可视化、CDC 无增量快照/DDL 同步。

**本轮目标**：在不破坏单 JAR / Java 8 / 最小依赖前提下，外科式补齐 **26 项能力**，把同步从"能跑通"提升到"生产可运维 + 轻量 ETL"。

---

## 二、范围（26 项 / 7 主题）

| 批 | 主题 | 功能（effort） |
|----|------|----------------|
| 1 | 数据加工管道 | ①行过滤 WHERE 下推(S) ②前后置 SQL(S) ③空值/默认值(S) ④内置转换函数(M) ⑤PII 脱敏(M) |
| 2 | 健壮性容错 | ①脏数据阈值 errorLimit(M) ②失败重试+退避(M) ③限速背压(M) |
| 3 | 数据校验对账 | ①行数对账(S) ②分片 checksum 深度对账(L) |
| 4 | 可运维监控告警 | ①执行审计(S) ②监控大盘(M) ③告警外发(M) ④预检增强(M) ⑤断点管理 UI(M) |
| 5 | CDC 增强 | ①心跳(S) ②监控指标(S) ③熔断+死信表(M) ④删除/重置/重快照(M) ⑤增量快照 signal 表(L) ⑥DDL 同步(L) |
| 6 | 多源/方言+调度 | ①SqlDialect 方言层(L) ②无主键/多列主键回退(M) ③全量 chunk 续传(L) ④任务依赖 DAG(L) ⑤资源隔离(L) |

**明确排除**：自定义脚本转换（Groovy/Aviator）——需引脚本引擎重依赖，撞"禁擅自新增第三方依赖"红线。如确需，后续用 JDK 自带 JSR-223 受限版单独评估。

---

## 三、硬约束

- Java 8 / Spring Boot 2.7.18 / 单模块单 JAR。
- 零或最优轻依赖。已有可复用：Debezium 1.9 嵌入式、HikariCP、mysql-connector-j、FastJSON2、MyBatis-Plus、自研调度、WebSocket STOMP、`dn_task_execution`、`CryptoUtil`。
- 告警外发用 JDK `HttpURLConnection`、哈希用 JDK `MessageDigest`/`CRC32`、JMX 用 JDK `ManagementFactory`——**零新依赖**。
- 限速令牌桶自研轻量实现（不引 Guava）。
- 外科式、可单测、可单 JAR 部署。第一版数据源仍锁 MySQL 协议族（MySQL/Doris/StarRocks）。

---

## 四、统一数据模型（跨切面对账后）

### 4.1 DnSyncJob 实体新增列（需 DDL `sql/25_sync_enhance.sql`）

| 列 | 类型 | 用途 |
|----|------|------|
| pre_sql / post_sql | LONGTEXT | 批1② 任务级前后置 SQL |
| error_limit_rows | INT NULL | 批2① 脏数据条数上限 |
| error_limit_ratio | DECIMAL(5,4) NULL | 批2① 脏数据比率上限 |
| retry_backoff_type | VARCHAR(20) | 批2② FIXED_DELAY/EXPONENTIAL |
| retry_backoff_delay | INT | 批2② 退避基数秒 |
| retry_backoff_jitter | DECIMAL(3,2) | 批2② 抖动 |
| rate_limit_mode | VARCHAR(10) | 批2③ NONE/ROWS/BATCHES |
| rate_limit_value | INT NULL | 批2③ 令牌/秒 |
| write_backpressure_size | INT NULL | 批2③ 写缓冲行上限 |
| failure_handling_mode | VARCHAR(10) | 批5③ skip/fail/warn |
| cdc_max_retries | INT | 批5③ CDC 重试次数（与引擎级 retryTimes 物理区分） |
| cdc_retry_delay_ms | INT | 批5③ CDC 重试延迟 |
| checksum_algo | VARCHAR(10) | 批3② CRC32/MD5 |
| checksum_bucket_count | INT | 批3② 分桶数 |
| priority | INT DEFAULT 5 | 批6⑤ 调度优先级 |

> `retryTimes`（已存在）= 引擎级重试次数；`cdcMaxRetries` = CDC 批/条重试，文档注明职责不交叉。任务依赖关系存独立表 `dn_sync_job_dependency`，**不**在 DnSyncJob 加列。

### 4.2 DnTaskExecution 实体新增列

| 列 | 用途 |
|----|------|
| attempt | INT，批2② 第几次重试 |
| executor_email | VARCHAR(128)，批4① 触发人（若已有 executor 列则复用，勿重复加） |

### 4.3 TableSyncConfig DTO 新增字段（纯 JSON，免 DDL，向后兼容）

`filterExpression`（批1①）、`preSql`/`postSql`（批1② 表级覆盖任务级）。

> 全量分片断点不放 JSON，统一落 `dn_sync_chunk_checkpoint` 表（支持多 chunk）。

### 4.4 FieldMapping DTO 新增字段（纯 JSON，免 DDL）

`defaultValue` + `nullHandling`(PASSTHROUGH/REPLACE_WITH_DEFAULT/SKIP_ROW，批1③)、`transformExpression`(批1④)、`maskingType` + `maskingSalt`(批1⑤)。

### 4.5 SyncContext 运行期新增

`globalPreSql`/`globalPostSql` + `getPreSql(table)`/`getPostSql(table)`；`rowValueProcessor`（值级加工管道）；`rateLimiter`；errorLimit 配置 + `erroneousRows` 收集器 + dirty 计数。

### 4.6 FieldMappingResolver.Resolved 一次性扩展（避免多批返工）

现 `{srcColumns, tgtColumns, pkTarget}` → 扩展为 `{srcColumns, tgtColumns, keysetColumns(多列主键，替代单 pkTarget), srcToFieldMapping(Map<源列,FieldMapping>)}`。批1③④⑤ 用 `srcToFieldMapping`，批6② 用 `keysetColumns`。

---

## 五、新增表（8 张，`sql/25_new_tables.sql`）

| 表 | 用途 | 关键列 |
|----|------|--------|
| dn_sync_job_audit | 批4① 配置/操作留痕，按 retention 清理 | job_id, operation_type, operator_*, change_detail(JSON) |
| dn_alert_rule | 批4③ 任务级告警规则 | job_id, alert_type, threshold, enabled, UK(job_id,alert_type) |
| dn_alert_history | 批4③ 告警发送历史+限流 | job_id, alert_type, message, delivery_status, sent_at |
| dn_checksum_diff | 批3② 对账差异分片 | job_id, table_name, bucket_id, source/target_checksum, is_match（避保留字 match） |
| dn_cdc_dead_letter | 批5③ CDC 死信 | job_id, source_table, origin_value(JSON), error_reason, error_type |
| dn_cdc_signal | 批5⑤ Debezium signal 表（须建在**源库**，结构含 id/type/data 三列） | id(VARCHAR), signal_type, signal_data |
| dn_sync_chunk_checkpoint | 批6③ 全量分片续传位点 | sync_job_id, table_name, chunk_seq, cursor_value, UK(job,table,seq) |
| dn_sync_job_dependency | 批6④ 任务级轻量 DAG | sync_job_id, upstream_sync_job_id, depends_all, execution_cycle |

DDL 草图见 workflow 输出 `reconciliation.newTables`。

---

## 六、共享组件（架构核心，必须先建）

| 组件 | 职责 | 被谁用 |
|------|------|--------|
| **SqlDialect** 方言层 (MysqlDialect/DorisDialect/StarRocksDialect/Factory) | 收敛 分页/UPSERT/DDL/类型映射/checksum SQL 差异（现散落 MysqlConnector/WriteSqlBuilder/TableSchemaService/TypeMappingService 四处） | 批1① 批3② 批6①②③ |
| **RowValueProcessor** 值级加工管道 | NullValueHandler → ValueTransformer → PiiMasker 串成一条链，引擎读取循环只调一个入口（避免行级散插打架） | 批1③④⑤ |
| **KeysetStrategy** + extractCursor | 单/多/无主键游标策略，提供可序列化游标值 | 批6②③ |
| **DataReconciliationService** + ChecksumBuilder | 行数对账与 checksum 对账共用，记 `dn_task_execution(taskType=DataReconciliation)` | 批3①② |
| **AlertService** + AlertSender（JDK HttpURLConnection，异步单线程队列防阻塞） | 告警外发，统一 webhook/钉钉/企业微信通道 | 批4③；批2② 批5③ 挂接 |
| **AuditLogService** | 操作/配置留痕，高危操作（断点重置/CDC 重置）记审计 | 批4①；批4⑤ 批5④ |
| **RateLimiter** 自研轻量令牌桶 | 任务**内**行/批读写节流（区别于批6⑤的任务**间**调度并发） | 批2③ |
| CDC offset/schema 清理 Mapper（deleteByJobId，单处定义） | 批4⑤ 与 批5④ 共用，避免重复签名 | 批4⑤ 批5④ |

---

## 七、跨切面冲突与消解（11 条，实现期必须遵守）

1. **线程池争用**：批2③(令牌桶/任务内) 与 批6⑤(优先级队列/任务间) 分层——批6⑤改调度层 `ThreadPoolExecutor+PriorityBlockingQueue` 替换 `newFixedThreadPool(4)`；批2③ 只在引擎读写循环 `RateLimiter.acquire`，不碰线程池。串行合并。
2. **SyncJobExecutor.run 签名**：一次性重构为 `run(jobId, triggerType, ExecutionOptions{user,email,attempt,priority})`，避免批4①/批6⑤/批2② 各自加参反复变更。
3. **Resolved 多批扩展**：第0层一次性扩展（见 4.6），三批共用。
4. **引擎读取循环三明治**：批1①③④⑤ + 批2①③ + 批6②③ 全插在 `FullSyncEngine`/`IncrementalSyncEngine` 同一段循环（约行 104-148）。消解：抽 `RowProcessor`（转换/脱敏/空值合一）+ `BatchWriter`（坏行重试/限速/断点合一）两个聚合入口，各功能往管道注册。
5. **DDL 同步 vs 方言层 都碰 TableSchemaService**：批6① 先把 buildDdl 搬入方言，TableSchemaService 委托方言，旧方法 `@Deprecated` 过渡。
6. **断点UI(批4⑤) vs CDC重置(批5④) 都碰 offset/schema**：Mapper.deleteByJobId 只在批5④定义；reset 端点归一到 `CdcController`；批4⑤ 仅管增量水位 checkpoint/reset，与 CDC offset 物理隔离。
7. **三种"断点"语义区分**：`incrementalValue`=增量水位(tableConfig JSON)、`chunkCheckpoint`=全量续传(新表)、`cdcOffset`=CDC位点(dn_cdc_offset)。批4⑤ UI 三类分页展示，字段不复用。
8. **告警表重叠**：已有 `dn_alert_config`(script 维度) 与新 `dn_alert_rule`(job 维度) 维度不同，不复用表；但 AlertSender 发送通道统一一套。
9. **retry 命名双轨**：CDC 字段加 `cdc` 前缀与引擎级 retry 物理区分。
10. **配置前缀统一**：全部 `datanote.sync.*`（批6 的 `dn.sync.*` 全改）。
11. **异常传播分级**：行级异常（脏数据）引擎内消化计 `erroneousRows` 不抛；表级/连接级异常抛给 executor 由批2② 退避重试。`ErrorClassifier` 判定。

---

## 八、落地顺序（6 层，分层串行 + 层内并行）

- **第0层 数据模型与 DDL 统一（串行先行，全局地基）**：合并最少 DDL（`25_sync_enhance.sql` 改 DnSyncJob/DnTaskExecution 列；`25_new_tables.sql` 8 张表；`25_task_dependency_alter.sql`）；DTO 纯 JSON 字段直接改；一次性扩展 `Resolved` 与 `SyncContext`。
- **第1层 共享基础组件**：(a) **批6① SqlDialect 最先**（改 FullSync/IncrementalSync/WriteSqlBuilder/TableSchemaService）；(b) 与 a 并行：批4① AuditLogService、批4③ AlertService。
- **第2层 引擎内值级加工**（依赖第1层 Resolved/Dialect）：批1③→④→⑤ 合并 RowValueProcessor 一次性改循环；批1① WHERE 下推（依赖 Dialect）；批1② 前后置 SQL（独立可并行）。
- **第3层 引擎健壮性**：批6② KeysetStrategy（依赖 Dialect）→ 批6③ 断点续传；批2①→②→③（内部串行）。两组都碰引擎循环，需 merge 协调。
- **第4层 运维监控对账**（多数独立可并行）：批3①→②；批4② 监控大盘；批4③ 告警挂接；批4④ 预检；CDC 族 批5①(纯配置最先)→②(JMX)→③(死信)→④(重置)→⑤(signal)；批4⑤ 断点UI 与 批5④ 共用 offset Mapper（先建 Mapper）。
- **第5层 调度编排（最后）**：批6⑤ 先换线程池（PriorityBlockingQueue+ThreadPoolExecutor）→ 批6④ 加拓扑排序与上游状态检查，与批2③ 的 executor 改动 merge。

---

## 九、测试策略（TDD）

- 纯逻辑类（FilterExpressionBuilder/ValueTransformer/PiiMasker/NullValueHandler/RateLimiter/ChecksumBuilder/ErrorClassifier/方言 SQL 生成）**先写单测后写实现**。
- 引擎级改动补集成测试：过滤后行数、脏数据阈值触发、重试退避次数、断点续传重跑不丢不重、多列主键游标分页正确性。
- 沿用现有测试风格（已有 `*Test.java` in `src/test/java/com/datanote/sync/...`）。
- 每批完成 `mvn -q -Dtest=... test` 局部验证 + 关键路径全量编译。

---

## 十、部署计划（服务器 38.76.183.50）

- 仍单 JAR，零新增运行时依赖。
- **里程碑式部署**（每个里程碑可独立验证、独立上线）：
  - M1 = 第0~2层（数据模型 + 方言层 + 数据加工管道）——立即可见的"轻量 ETL"能力。
  - M2 = 第3层（健壮性 + 多主键/续传）。
  - M3 = 第4层（运维监控告警 + CDC 增强）。
  - M4 = 第5层（调度编排）。
- 每里程碑流程：本地 `mvn package` → scp 替换 `/opt/datanote/target/datanote-1.0.0.jar` → 跑该里程碑 DDL → `systemctl restart datanote` → 验证（注意：SSH 上行须分块上传，见记忆 `datanote-deploy-topology`）。
- DDL 全部 `IF NOT EXISTS` / 列加 `IF NOT EXISTS` 兼容重跑。

---

## 十一、验收标准

- 26 项功能各有单测/集成测试通过，关键路径 MySQL→MySQL 与 MySQL→Doris 端到端跑通。
- 行过滤/转换/脱敏/默认值在真实任务生效；脏数据阈值与重试退避按配置触发；行数对账给出结果。
- 监控大盘实时显示进度/速率/lag；告警可外发到 Webhook；审计可查配置变更。
- CDC 心跳/指标/死信/重置/增量快照可用；DDL 同步源端加列后目标自动 ALTER。
- 多列主键表可同步；全量中断重跑从 chunk 续传；任务依赖按上游成功触发。
- 全程零 Kafka、零新重依赖、单 JAR 部署。

---

## 十二、风险

1. **改动面巨大**：26 项触及几乎所有同步类。靠"分层串行 + 共享组件先行 + 充分单测护栏 + 里程碑部署"控制回归。每层完成即编译+测试+（可选）部署验证。
2. **引擎循环并发改动冲突**：多功能改同一段循环，靠 RowProcessor/BatchWriter 聚合入口 + 同一开发串行（workflow 子代理隔离 worktree）规避。
3. **方言层重构回归**：先搬逻辑、旧方法 @Deprecated、存量单测护栏。
4. **CDC signal 表/DDL 同步**（批5⑤⑥，L）：Debezium 1.9.7 DDD-3 配置与一致性验证复杂；DDL 同步需消费端自解析 schema change 生成 ALTER。这两项最重，排最后，失败不影响前序里程碑上线。
5. **L 重活整体体量**：8 个 L 项工作量大。若实施中发现某 L 项性价比骤降或风险过高，回退到对应里程碑边界、标记后续专项轮（已在文档留出边界）。
