# DataNote

**轻量级一站式数据开发平台** — 单 JAR 包即可运行，覆盖数据同步、SQL 开发、任务调度、数据地图、数据质量、AI 辅助全链路。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-green.svg)](https://spring.io/projects/spring-boot)

---

## 为什么做 DataNote

市面上的数据开发平台要么太重（DataSphereStudio 需要 Linkis + 多个子项目），要么太散（DolphinScheduler 只管调度、SeaTunnel 只管同步）。

DataNote 的目标是：**一个 JAR 包 + 一个 HTML 文件，5 分钟跑起来一个完整的数据开发平台。**

## 核心功能

| 模块 | 功能 |
|------|------|
| **数据同步** | MySQL → Hive 全量/增量同步（DataX 引擎），字段映射，一键建表 |
| **SQL 开发** | Monaco Editor 在线 IDE，HiveSQL 执行，结果排序/筛选/导出 CSV |
| **任务调度** | Cron 定时调度、DAG 依赖管理、失败重试（指数退避）、超时告警 |
| **数据地图** | AI 智能搜索、表详情（字段/预览/探查/DDL）、收藏、评论 |
| **数据质量** | 规则配置、质量检查、检查历史 |
| **指标管理** | 指标定义 CRUD、分类管理 |
| **AI 辅助** | NL2SQL、SQL 解释/优化、AI 搜索、AI 生成表名/Cron、语音输入 |

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 8 + Spring Boot 2.7 + MyBatis-Plus 3.5 |
| 前端 | 单文件 SPA（vanilla JS，零框架依赖） |
| 数据库 | MySQL 8.0（元数据） + Apache Hive（数仓） |
| 数据同步 | DataX |
| AI | Claude API（可选，不配置不影响核心功能） |

## 快速开始

分两步部署：先装 Hadoop + Hive 环境，再装 DataNote。

```bash
git clone https://github.com/datanote1018/datanote.git
cd datanote

# 第一步：安装 Hadoop + Hive（Docker，如果已有环境可跳过）
./setup-hive.sh
./setup-hive.sh test    # 验证 Hive 可用

# 第二步：安装 DataNote（自动编译、建库、启动）
./setup-datanote.sh

# 访问 http://localhost:8099
# → 系统管理 → 数据源管理 → 配置 Hive 连接 → 完成
```

**环境要求**：Docker Desktop（第一步）/ Java 8+（第二步）

详细说明见 [部署指南](DEPLOY.md)。

## 配置说明

**MySQL 连接**通过启动参数或环境变量配置：

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `DB_HOST` | MySQL 地址 | 127.0.0.1 |
| `DB_PORT` | MySQL 端口 | 3306 |
| `DB_PASSWORD` | MySQL 密码 | （必填） |

**Hive / HDFS / DataX / AI** 在页面上配置：系统管理 → 数据源管理 / 环境配置 / AI 配置。

## 项目结构

```
datanote/
├── src/main/java/com/datanote/
│   ├── controller/    # REST API
│   ├── service/       # 业务逻辑
│   ├── mapper/        # MyBatis-Plus Mapper
│   ├── model/         # 实体类
│   ├── config/        # 配置类
│   └── util/          # 工具类
├── src/main/resources/
│   ├── static/workspace.html  # 前端 SPA
│   └── application.yml        # 配置文件
├── sql/init-all.sql   # 数据库初始化脚本
├── setup-hive.sh      # 第一步：安装 Hadoop + Hive
├── setup-datanote.sh  # 第二步：安装 DataNote
├── Dockerfile         # Docker 镜像构建
├── DEPLOY.md          # 部署指南
├── LICENSE            # Apache 2.0
└── pom.xml
```

## 与同类项目对比

| 特性 | DataNote | DataSphereStudio | Dinky | qData |
|------|----------|-----------------|-------|-------|
| 部署方式 | 单 JAR 包 | Linkis + 多子项目 | Docker | 微服务 |
| 前端 | 单 HTML 文件 | React 多模块 | Ant Design Pro | Vue 3 |
| 数据同步 | 内置 DataX | Exchangis | FlinkCDC | 多引擎 |
| AI 辅助 | 内置 NL2SQL/AI 搜索 | 无 | 无 | 无 |
| 数据质量 | 内置 | Qualitis（独立项目） | 无 | 有 |
| 上手时间 | 5 分钟 | 1-2 天 | 30 分钟 | 1 小时 |

## 参与贡献

欢迎提交 Issue 和 Pull Request！请阅读 [贡献指南](CONTRIBUTING.md) 了解详情。

## 许可证

[Apache License 2.0](LICENSE)
