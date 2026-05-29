# DataNote 关系库同步 — 升级 backlog 与本轮范围（2026-05-29）

来源：13-agent workflow（6 深读现状 + 6 互联网调研 + 1 综合），run `wf_98e52550-99d`。

## 本轮实现范围（外科式、零新依赖、可单测、单 JAR 可部署）

| ID | 类别 | 标题 |
|----|------|------|
| B1 | perf | JDBC URL 加 rewriteBatchedStatements + prepStmt 缓存 |
| B5 | perf | writePs 提到分页循环外复用 + 读端 setFetchSize 流式 |
| B16 | reliability | HikariCP 池配置完善(maxLifetime/keepalive/idleTimeout/minIdle) |
| B2 | correctness | evict 接通数据源 update/delete 生命周期 |
| B10 | correctness | 类型映射覆盖度与精度(UNSIGNED/时间精度/VARCHAR 上限) |
| B11 | correctness | TableSchemaService DDL 安全加固(comment 转义/大小写/varchar 兜底) |
| B12 | correctness | FieldMappingResolver 重复 target + syncTsField 配置期校验 |
| B3 | correctness | 增量断点按表成功即回写(不再整体回写丢断点) |
| B6 | reliability | 启动期补偿孤儿 RUNNING 执行记录(排除 CDC) |
| B19 | correctness | 消除 doRun 重复 selectById 与 exec==null 静默丢更新 |
| B8 | correctness | save 端点服务端校验(必填/JSON/cron/增量字段/写模式) |
| B7 | usability | FULL/INCREMENTAL 停止接口(打通 stopped 死代码) |
| B18 | reliability | 消费 timeoutSeconds 给 doRun 加超时终止(依赖 B7) |
| B24 | usability | 同步预检端点(连通性/源表主键/目标库表/cron) |
| B4 | correctness | CDC 逻辑删除漏配字段时快速失败而非物理删除 |
| B20 | correctness | 修复 CDC server.id 取模碰撞 |
| B21 | correctness | CDC UPDATE 主键变更删旧行 |
| B9 | correctness | CDC 改 handleBatch 攒批写 + 落库后才 markProcessed(消除静默丢数) |
| B13 | observability | CDC 接 Debezium JMX 指标(真实 lag/吞吐)，+2 列 DDL |
| B15 | reliability | 抽公共建表列工具，消除 Executor/CdcEngineManager 重复 |
| B23 | reliability | 补 controller 层 + 关键路径测试 |

## 暂缓（后续专项轮）

B14(Doris group_commit) / B17(时间戳类型感知) / B22(decryptSafe 区分密钥错误) /
B25(进度百分比节流) / B26(无主键/多列主键回退) / B27(CDC 熔断+死信表) /
B28(全量 chunk 断点续传) / B29(表级并行+限速) / B30(checksum 对账) /
B31(Doris 建表参数化) / B32(CDC 删除/重置入口) / B33(pkCache 失效) /
B34(CDC 增量快照 signal 表) / B35(SqlDialect 方言层)。

## 部署目标（已侦察）

- 服务器 38.76.183.50 / Ubuntu 24.04 / Java 17，datanote 跑 systemd `datanote.service`
- jar=`/opt/datanote/target/datanote-1.0.0.jar`，-Xmx1g
- 元数据 MySQL 8.0.46 @127.0.0.1:3307(容器)，root/datanote123，binlog=ON/ROW/FULL(CDC 就绪)
- Doris FE 9030 ods 库；CRYPTO_KEY=DataNote_AES_Key
- 部署方式：本地编译 jar → scp 替换 → 跑新 DDL(25) → 重启 service → 验证
