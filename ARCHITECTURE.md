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

默认 Doris 连接：

```yaml
doris:
  host: 38.76.183.50
  query-port: 9030
  database: ods
  username: root
  password: "123456"
```

## Core Flow

1. 元数据接口读取源 MySQL 的库、表、字段。
2. `HiveService`（保留类名兼容旧代码）生成 Doris `ENGINE=OLAP` 建表语句并通过 MySQL JDBC 执行。
3. `DataxService` 生成 `mysqlreader -> mysqlwriter` DataX JSON，目标 JDBC 为 Doris FE `38.76.183.50:9030/ods`。
4. SQL 工作台通过 `/api/doris/execute` 执行 Doris SQL；旧 `/api/hive/*` 路径保留兼容。

## Main Modules

| 模块 | 说明 |
|---|---|
| `HiveConfig` | Doris JDBC/HikariCP 配置，兼容旧配置键 |
| `HiveService` | Doris DDL 生成、SQL 执行、结果集返回 |
| `DataxService` | DataX JSON 生成和任务执行 |
| `SystemConfigController` | Doris 连接测试、保存和热加载 |
| `DataMapService` | Doris 库表元数据、预览、探查 |
