# 数据同步模块 生产化补全 — 总体设计（方案C 全面对标）

> 状态：设计稿（用户已确认范围与关键决策）
> 定位红线：单 JAR / Java 8 / Spring Boot 2.7 / MyBatis-Plus / JDBC 源 → Apache Doris / 嵌入式 Debezium。优先复用现有 `com.datanote.sync.*` 与既有表，不引 Kafka/Flink/ZK/Prometheus 等重中间件；外科式增量改造，不推倒重来。

## 一、背景（深度调研结论）

DataNote 数据同步内核已工业级：复合游标增量+UPSERT幂等+断点续传、全量 keyset 分片续传、嵌入式 Debezium CDC（offset/schema 历史落库、at-least-once+下游幂等）、脏数据熔断、退避重试、令牌桶限流、加工/脱敏、DAG 依赖、秒级 cron、行数+分桶 checksum、完整前端。轻量赛道已超 DataX。

**核心缺口（4 块）**：① 对账不闭环（只 COUNT+全表分桶，无二分定位/主键级 diff/修复）② 脏数据不可见（全量/增量坏行只计数丢弃，无 DLQ/重放）③ 可观测散落（无 run 级指标聚合表、无 lag 阈值告警）④ Doris 写入非原生（逐行 JDBC，无 Stream Load Label 幂等）。次级：源端锁死 MySQL 协议族、Schema 演进仅加列、告警渠道窄。

## 二、锁定决策（用户确认）

| # | 决策 | 取值 |
|---|---|---|
| 1 | 范围 | **方案C 全面对标**，分里程碑交付 |
| 2 | 源端扩展 | **MySQL(现) + PostgreSQL + Oracle + SQLServer** |
| 3 | Doris 写入 | **接入原生 Stream Load**（JDK HttpURLConnection，零新依赖；默认仍 JDBC，显式开启） |
| 4 | 部署形态 | **单实例**（免分布式调度锁） |

## 三、设计原则

1. 复用现有 `dn_sync_job`/`dn_task_execution`/`dn_sync_chunk_checkpoint`/`dn_cdc_*` 与 sync 包结构；新表只在确无承载体时增。
2. 不动 SyncEngine 主流程语义；新能力以可配开关挂入（默认安全）。
3. 跨方言对账坚持「只比行数 + 应用层统一规范化算 hash」，**绝不依赖两端原生 MD5 字节一致**（git log 已记录踩坑）。
4. 仅 JDK 自带能力优先（Stream Load 用 HttpURLConnection）；告警邮件等可选依赖按需引、默认关。
5. 前端单文件 `workspace.html` viewDbSync，外科式加面板/抽屉页签，不重写。

## 四、里程碑分解

> 后端在 `com.datanote.sync.*`（多文件，可并行）；前端在 workspace.html viewDbSync（单文件，集中改）。每里程碑独立交付 + 测试 + 部署。

### P0 — 正确性闭环（最痛、零新依赖、不动引擎主流程）
- **DS-M1 脏数据 DLQ 错误行落表 + 重放**：新表 `dn_sync_error_row`（job_id/run_id/source_table/pk/raw_row/error_code/error_msg/stage/attempt/created_at）；`BatchWriter` 逐行回退定位坏行时落表（可配开关，默认开）；`/api/sync-job/{id}/error-rows` 查询 + 重放（按主键 UPSERT 幂等）；前端详情抽屉加「错误行」页签。
- **DS-M2 对账闭环（分段二分 + 主键级 diff）**：`DataReconciliationService` 扩展——先同条件 COUNT 快判（目标按映射套过滤），不等再 Datafold 式分段（主键 min/max 切 bisection=10 段，两端下推 count+规范化 MD5 折叠，仅不匹配段递归二分，小于阈值拉全行主键级 diff），输出缺失/多余/不一致清单；复用 `ChecksumSqlBuilder`。

### P1 — 可观测 / 性能 / 告警 / 易用
- **DS-M3 run 级指标聚合表**：新表 `dn_sync_run`（run_id/job_id/table/read/write/skip/reject/bytes/rate/耗时/断点水位/cdc_lag）；run_id 贯穿日志；大盘补 run 级聚合（失败率/速率/今日行数/lag 趋势）。复用 `SyncContext` 计数器。
- **DS-M4 Doris Stream Load 写入通道**：`BatchWriter` 旁加可选 Stream Load 通道（HttpURLConnection chunked），稳定 Label=jobId_table_chunk_watermark 实现导入级幂等；攒批+可选 Group Commit；新增「写入通道」配置项（默认 JDBC）。
- **DS-M5 告警增强**：`AlertService` 扩触发源——CDC lag 超阈、死信堆积超阈、对账不一致；`DnSyncJob`/`AlertConfig` 加阈值与接收人；钉钉加签+@手机号；可选邮件（spring-boot-starter-mail 按需）。
- **DS-M6 连接测试 + 库表列预览 + DDL 预览 + JSON 校验**：数据源/源目标库「测试连接」（复用 precheck + CDC 源校验 binlog_format/log_bin/权限）；库→表→列三级 + 样本前 N 行预览；保存前 DDL 预览；过滤/转换 JSON 前端校验。

### P2 — 高级能力
- **DS-M7 Schema 漂移分级处理**：源新增列自动 Doris ALTER ADD COLUMN；改类型/删列/改主键 → 告警+暂停该表待人工确认，绝不静默损坏。复用 `DdlSupport`。
- **DS-M8 异构源：PostgreSQL**：`DbConnector` 新增 `PostgresConnector`（PG 元数据/分页方言）+ `ConnectionManager` 按数据源 type 选驱动/URL + CDC Debezium PG + `pgToDoris` 类型映射。需引 postgresql JDBC + debezium-connector-postgres 依赖。
- **DS-M9 异构源：Oracle + SQLServer**：同 M8 模式补 Oracle/SQLServer connector + 类型映射 + CDC connector。需引对应 JDBC/Debezium 依赖。
- **DS-M10 整库同步 + 表名正则批量 + 分表汇聚**：选库后按表名正则（白/黑名单）批量生成多表配置；多源表正则汇聚到单 Doris 表（多对一）；新增表可选自动纳入或告警。复用多表配置结构。
- **DS-M11 增量删除捕获（可选）**：增量（非 CDC）模式可选基于软删标记列+水位捕获逻辑删除；或文档明确「物理删除用 CDC」。

## 五、交付节奏

P0(M1→M2) → P1(M3→M6) → P2(M7→M11)。每里程碑：读现有代码→设计→可纯函数化逻辑 TDD→实现→测试→部署→验证。后端多文件可并行（worktree agents），前端单文件集中改。新增依赖（PG/Oracle/SQLServer JDBC+Debezium、可选 mail）在对应里程碑引入并标注。

## 六、测试策略

沿用 JUnit + Testcontainers E2E。新增可纯函数化逻辑（分段二分边界、规范化 hash、DLQ 行映射、Stream Load Label 构造、类型映射、正则批量生成）优先单测；对账/Stream Load 关键路径补集成验证。

## 七、风险与护栏

1. 跨方言对账 hash 一致性（坚持行数+规范化，不依赖原生 MD5）。
2. Stream Load 失败降级回 JDBC，不阻断同步。
3. 异构源扩展回归面大 → 分库分批、每库独立验证，不一次性重写 connector 层。
4. DLQ 原始行可能含敏感数据 → 复用 PII 脱敏；提供「仅存主键+错误」可配模式。
5. Schema 漂移危险变更默认「告警+暂停」，绝不静默 ALTER 破坏。

## 八、待评审项

1. 里程碑交付顺序确认（P0→P1→P2）。
2. DLQ 原始行存储：完整行 JSON vs 仅主键+错误（合规/存储取舍）。
3. 对账差异行是否要「自动修复」还是仅出报告（首版建议仅报告）。
4. 异构源 JDBC/Debezium 依赖引入会增大 jar 体积，确认可接受。
