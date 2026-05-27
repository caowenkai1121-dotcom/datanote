# DataNote 部署指南（Doris 版）

本版本将数仓执行引擎从 Hive 切到 Doris。默认 Doris 连接为：

| 项 | 值 |
|---|---|
| Host | `38.76.183.50` |
| Query Port | `9030` |
| Database | `ods` |
| Username | `root` |
| Password | `123456` |

## 1. 准备服务

必需服务：

| 服务 | 用途 | 默认地址 |
|---|---|---|
| MySQL 8.0 | DataNote 元数据库 | `127.0.0.1:3306` |
| Doris FE | 数仓建表、SQL 执行、DataX 写入 | `38.76.183.50:9030` |

可选服务：

| 服务 | 用途 |
|---|---|
| DataX | MySQL 源库同步到 Doris ODS 表 |
| DolphinScheduler | 脚本/同步任务上线调度 |

## 2. 配置

编辑 `datanote.conf`：

```bash
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_PASSWORD=root

DORIS_HOST=38.76.183.50
DORIS_QUERY_PORT=9030
DORIS_DATABASE=ods
DORIS_USERNAME=root
DORIS_PASSWORD=123456
```

## 3. 启动 DataX（可选）

```bash
./setup-datax.sh start
```

不需要数据同步时可以跳过；SQL 开发、数据地图、调度等功能仍可使用。

## 4. 启动 DataNote

```bash
./setup-datanote.sh
```

访问 `http://localhost:8099`，进入「系统管理 → 数据源管理」测试 Doris 连接。

## 常见问题

**Doris 连接失败**

检查 `38.76.183.50:9030` 是否可达，以及 `root / 123456` 是否有 `ods` 库的建表、查询、写入权限。

**DataX 同步失败**

DataX 现在生成 `mysqlreader -> mysqlwriter` 任务，写入 Doris 的 MySQL 协议端口 `9030`。确认 DataX 容器可以访问源 MySQL 和 `38.76.183.50:9030`。
