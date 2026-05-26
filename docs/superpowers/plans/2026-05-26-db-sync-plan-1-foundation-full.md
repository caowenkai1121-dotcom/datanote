# 关系库同步 · 计划 1：地基与全量同步 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 DataNote 补齐"关系型数据库之间全量同步"能力，跑通 MySQL→MySQL 全量同步，含任务管理、执行记录、实时日志。

**Architecture:** 在 DataNote 单模块内新增 `com.datanote.sync` 包。纯 JDBC 进程内引擎：用 HikariCP 池化连接，游标分页读源表、批量 upsert 写目标表。复用现有 `dn_datasource`/`CryptoUtil`/`dn_task_execution`/`LogBroadcastService`。本计划不含增量、自动建表、CDC（后续计划）。

**Tech Stack:** Java 8、Spring Boot 2.7.18、MyBatis-Plus 3.5.5、HikariCP（已在 classpath）、mysql-connector-j 8.0.33、FastJSON2 2.0.43、JUnit 5。

> **设计来源：** `docs/superpowers/specs/2026-05-26-datanote-db-sync-design.md`
> **环境说明：** 本地 Windows + PowerShell 开发编译，部署到 Linux 服务器 38.76.183.50。测试命令用 PowerShell 写法（`-D` 参数加引号）。本计划任务 3、4、6 的分页/SQL 纯逻辑走 TDD；任务 5、7、10 涉及真实数据库的部分用"实现 + 真实库验证"。

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `sql/23_db_sync_tables.sql` | 新增 3 张表 DDL（本计划只用 `dn_sync_job`，另两张为后续计划预建） |
| `src/main/java/com/datanote/model/DnSyncJob.java` | `dn_sync_job` 实体 |
| `src/main/java/com/datanote/mapper/DnSyncJobMapper.java` | MyBatis-Plus Mapper |
| `src/main/java/com/datanote/sync/dto/TableSyncConfig.java` | 单表同步配置（`table_config` JSON 元素） |
| `src/main/java/com/datanote/sync/dto/SyncContext.java` | 一次同步运行的上下文与计数器 |
| `src/main/java/com/datanote/sync/util/SqlIdentifiers.java` | 标识符白名单校验 + 反引号引用 |
| `src/main/java/com/datanote/sync/util/WriteSqlBuilder.java` | 生成 UPSERT/INSERT 写入 SQL |
| `src/main/java/com/datanote/sync/connector/ConnectionManager.java` | 按 dsId 缓存 HikariCP 池 |
| `src/main/java/com/datanote/sync/connector/TableMeta.java` | 表元数据（列 + 主键） |
| `src/main/java/com/datanote/sync/connector/DbConnector.java` | 连接器接口 |
| `src/main/java/com/datanote/sync/connector/MysqlConnector.java` | MySQL 协议族连接器实现 |
| `src/main/java/com/datanote/sync/engine/FullSyncEngine.java` | 全量同步引擎（游标分页 + 批量写） |
| `src/main/java/com/datanote/sync/service/SyncJobService.java` | 任务 CRUD |
| `src/main/java/com/datanote/sync/service/SyncJobExecutor.java` | 全量执行入口：建执行记录 + 调引擎 + 推日志 |
| `src/main/java/com/datanote/sync/controller/SyncJobController.java` | REST 接口 |
| `src/test/java/com/datanote/sync/util/SqlIdentifiersTest.java` | 标识符校验测试 |
| `src/test/java/com/datanote/sync/util/WriteSqlBuilderTest.java` | 写入 SQL 生成测试 |
| `src/test/java/com/datanote/sync/connector/MysqlConnectorTest.java` | 主键提取 / 分页 SQL 测试 |

---

## Task 1: 新增同步任务表 DDL

**Files:**
- Create: `sql/23_db_sync_tables.sql`

- [ ] **Step 1: 写建表 SQL**

创建 `sql/23_db_sync_tables.sql`：

```sql
-- 关系库同步功能建表（计划1只用 dn_sync_job，dn_cdc_* 为 CDC 计划预建）
USE datanote;

-- 关系库同步任务定义
CREATE TABLE IF NOT EXISTS dn_sync_job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    job_name        VARCHAR(200) NOT NULL COMMENT '任务名称',
    source_ds_id    BIGINT       NOT NULL COMMENT '源数据源ID（dn_datasource）',
    target_ds_id    BIGINT       NOT NULL COMMENT '目标数据源ID（dn_datasource）',
    source_db       VARCHAR(100) DEFAULT NULL COMMENT '源库',
    target_db       VARCHAR(100) DEFAULT NULL COMMENT '目标库',
    sync_mode       VARCHAR(20)  NOT NULL DEFAULT 'FULL' COMMENT 'FULL/INCREMENTAL/CDC',
    table_config    LONGTEXT     COMMENT 'JSON: [{sourceTable,targetTable,createTargetTable,incrementalField,incrementalType,incrementalValue}]',
    field_mapping   LONGTEXT     COMMENT 'JSON 字段映射(可选)',
    write_mode      VARCHAR(20)  DEFAULT 'UPSERT' COMMENT 'UPSERT/INSERT/INSERT_IGNORE',
    batch_size      INT          DEFAULT 1000 COMMENT '批量大小',
    schedule_cron   VARCHAR(64)  DEFAULT NULL COMMENT 'Cron(全量/增量用)',
    schedule_status VARCHAR(16)  DEFAULT 'offline' COMMENT 'online/offline',
    status          VARCHAR(20)  DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/STOPPED/PAUSED/FAILED',
    retry_times     INT          DEFAULT 1 COMMENT '失败重试次数',
    timeout_seconds INT          DEFAULT 0 COMMENT '超时(秒)',
    created_by      VARCHAR(50)  DEFAULT '',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_sync_mode (sync_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系库同步任务';

-- CDC binlog 位点（CDC 计划使用）
CREATE TABLE IF NOT EXISTS dn_cdc_offset (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    job_id       BIGINT       NOT NULL COMMENT '关联 dn_sync_job.id',
    offset_key   VARCHAR(512) NOT NULL COMMENT 'Debezium offset key',
    offset_value LONGTEXT     COMMENT 'Debezium offset value',
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_key (job_id, offset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC binlog 位点';

-- CDC 表结构变更历史（CDC 计划使用）
CREATE TABLE IF NOT EXISTS dn_cdc_schema_history (
    id           BIGINT     NOT NULL AUTO_INCREMENT,
    job_id       BIGINT     NOT NULL COMMENT '关联 dn_sync_job.id',
    history_data LONGTEXT   COMMENT 'Debezium schema history 一条记录(JSON)',
    created_at   DATETIME   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC 表结构变更历史';
```

- [ ] **Step 2: 在本地 MySQL 执行并验证**

Run（PowerShell，替换 `<DB_PASSWORD>` 为本地 datanote 库密码）：

```powershell
mysql -uroot -p"<DB_PASSWORD>" datanote -e "source sql/23_db_sync_tables.sql; SHOW TABLES LIKE 'dn_sync_job'; DESC dn_sync_job;"
```

Expected: 输出包含 `dn_sync_job` 表及其 19 个字段定义。

- [ ] **Step 3: Commit**

```powershell
git add sql/23_db_sync_tables.sql
git commit -m "feat(sync): 新增关系库同步任务表 DDL"
```

---

## Task 2: DnSyncJob 实体与 Mapper

**Files:**
- Create: `src/main/java/com/datanote/model/DnSyncJob.java`
- Create: `src/main/java/com/datanote/mapper/DnSyncJobMapper.java`

- [ ] **Step 1: 写实体类**

创建 `src/main/java/com/datanote/model/DnSyncJob.java`：

```java
package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 关系库同步任务实体 — 对应 dn_sync_job 表
 */
@Data
@TableName("dn_sync_job")
public class DnSyncJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobName;
    private Long sourceDsId;
    private Long targetDsId;
    private String sourceDb;
    private String targetDb;
    private String syncMode;        // FULL/INCREMENTAL/CDC
    private String tableConfig;     // JSON
    private String fieldMapping;    // JSON
    private String writeMode;       // UPSERT/INSERT/INSERT_IGNORE
    private Integer batchSize;
    private String scheduleCron;
    private String scheduleStatus;
    private String status;          // CREATED/RUNNING/STOPPED/PAUSED/FAILED
    private Integer retryTimes;
    private Integer timeoutSeconds;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 写 Mapper**

创建 `src/main/java/com/datanote/mapper/DnSyncJobMapper.java`：

```java
package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnSyncJob;

/**
 * 关系库同步任务 Mapper
 */
public interface DnSyncJobMapper extends BaseMapper<DnSyncJob> {
}
```

- [ ] **Step 3: 编译验证**

Run:

```powershell
mvn -q -o compile
```

Expected: BUILD SUCCESS（若首次需联网下载依赖，去掉 `-o`）。

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/datanote/model/DnSyncJob.java src/main/java/com/datanote/mapper/DnSyncJobMapper.java
git commit -m "feat(sync): 新增 DnSyncJob 实体与 Mapper"
```

---

## Task 3: 标识符校验工具（TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/SqlIdentifiers.java`
- Test: `src/test/java/com/datanote/sync/util/SqlIdentifiersTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/datanote/sync/util/SqlIdentifiersTest.java`：

```java
package com.datanote.sync.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlIdentifiersTest {

    @Test
    void quote_wrapsValidIdentifierWithBackticks() {
        assertEquals("`user_id`", SqlIdentifiers.quote("user_id"));
        assertEquals("`Order2`", SqlIdentifiers.quote("Order2"));
    }

    @Test
    void quote_rejectsInjectionAttempts() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("a`b"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("a; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote("a b"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote(""));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifiers.quote(null));
    }

    @Test
    void isValid_returnsBooleanWithoutThrowing() {
        assertTrue(SqlIdentifiers.isValid("col_1"));
        assertFalse(SqlIdentifiers.isValid("col-1"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q -o test "-Dtest=SqlIdentifiersTest"
```

Expected: 编译失败（`SqlIdentifiers` 不存在）—— 这是预期的"红"。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/datanote/sync/util/SqlIdentifiers.java`：

```java
package com.datanote.sync.util;

import java.util.regex.Pattern;

/**
 * SQL 标识符（库名/表名/列名）安全处理：白名单校验 + 反引号引用，杜绝注入。
 */
public final class SqlIdentifiers {

    /** 仅允许字母、数字、下划线、$ */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_$]+$");

    private SqlIdentifiers() {
    }

    public static boolean isValid(String identifier) {
        return identifier != null && VALID.matcher(identifier).matches();
    }

    /**
     * 校验并用反引号包裹标识符。
     *
     * @throws IllegalArgumentException 非法标识符
     */
    public static String quote(String identifier) {
        if (!isValid(identifier)) {
            throw new IllegalArgumentException("非法 SQL 标识符: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
mvn -q -o test "-Dtest=SqlIdentifiersTest"
```

Expected: BUILD SUCCESS，3 个测试通过。

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/datanote/sync/util/SqlIdentifiers.java src/test/java/com/datanote/sync/util/SqlIdentifiersTest.java
git commit -m "feat(sync): 标识符白名单校验工具"
```

---

## Task 4: 写入 SQL 生成器（TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/WriteSqlBuilder.java`
- Test: `src/test/java/com/datanote/sync/util/WriteSqlBuilderTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/datanote/sync/util/WriteSqlBuilderTest.java`：

```java
package com.datanote.sync.util;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteSqlBuilderTest {

    private final List<String> cols = Arrays.asList("id", "name", "age");
    private final List<String> pk = Collections.singletonList("id");

    @Test
    void upsert_mysql_buildsOnDuplicateKeyUpdate() {
        String sql = WriteSqlBuilder.build("UPSERT", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `t_user` (`id`, `name`, `age`) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `age` = VALUES(`age`)",
            sql);
    }

    @Test
    void insert_buildsPlainInsert() {
        String sql = WriteSqlBuilder.build("INSERT", "t_user", cols, pk);
        assertEquals(
            "INSERT INTO `t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void insertIgnore_buildsInsertIgnore() {
        String sql = WriteSqlBuilder.build("INSERT_IGNORE", "t_user", cols, pk);
        assertEquals(
            "INSERT IGNORE INTO `t_user` (`id`, `name`, `age`) VALUES (?, ?, ?)", sql);
    }

    @Test
    void upsert_withNoNonPkColumns_fallsBackToInsertIgnore() {
        String sql = WriteSqlBuilder.build("UPSERT", "t_kv", Collections.singletonList("id"),
            Collections.singletonList("id"));
        assertEquals("INSERT IGNORE INTO `t_kv` (`id`) VALUES (?)", sql);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -q -o test "-Dtest=WriteSqlBuilderTest"
```

Expected: 编译失败（`WriteSqlBuilder` 不存在）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/datanote/sync/util/WriteSqlBuilder.java`：

```java
package com.datanote.sync.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成写入目标表的 SQL（参数占位符 ?，值由 PreparedStatement 绑定）。
 * 目标：MySQL 协议族（MySQL/Doris/StarRocks）。
 */
public final class WriteSqlBuilder {

    private WriteSqlBuilder() {
    }

    /**
     * @param writeMode UPSERT / INSERT / INSERT_IGNORE
     * @param table     目标表名
     * @param columns   全部列（按绑定顺序）
     * @param pkColumns 主键列
     */
    public static String build(String writeMode, String table, List<String> columns, List<String> pkColumns) {
        String quotedTable = SqlIdentifiers.quote(table);
        String colList = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String base = "INSERT INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";

        if ("INSERT_IGNORE".equals(writeMode)) {
            return "INSERT IGNORE INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";
        }
        if ("UPSERT".equals(writeMode)) {
            List<String> setClauses = new ArrayList<>();
            for (String col : columns) {
                if (!pkColumns.contains(col)) {
                    String q = SqlIdentifiers.quote(col);
                    setClauses.add(q + " = VALUES(" + q + ")");
                }
            }
            if (setClauses.isEmpty()) {
                // 没有非主键列可更新，退化为 INSERT IGNORE
                return "INSERT IGNORE INTO " + quotedTable + " (" + colList + ") VALUES (" + placeholders + ")";
            }
            return base + " ON DUPLICATE KEY UPDATE " + String.join(", ", setClauses);
        }
        // 默认 INSERT
        return base;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
mvn -q -o test "-Dtest=WriteSqlBuilderTest"
```

Expected: BUILD SUCCESS，4 个测试通过。

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/datanote/sync/util/WriteSqlBuilder.java src/test/java/com/datanote/sync/util/WriteSqlBuilderTest.java
git commit -m "feat(sync): 写入 SQL 生成器（UPSERT/INSERT/IGNORE）"
```

---

## Task 5: 连接管理器（HikariCP 池）

**Files:**
- Create: `src/main/java/com/datanote/sync/connector/ConnectionManager.java`

> HikariCP 随 `spring-boot-starter-jdbc` 已在 classpath，直接 import `com.zaxxer.hikari.*`，无需改 pom。

- [ ] **Step 1: 写实现**

创建 `src/main/java/com/datanote/sync/connector/ConnectionManager.java`：

```java
package com.datanote.sync.connector;

import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.model.DnDatasource;
import com.datanote.util.CryptoUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按数据源 ID 缓存 HikariCP 连接池（MySQL 协议族）。
 */
@Slf4j
@Component
public class ConnectionManager {

    private final DnDatasourceMapper datasourceMapper;

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    @Value("${datanote.sync.pool-max-size:5}")
    private int poolMaxSize;

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public ConnectionManager(DnDatasourceMapper datasourceMapper) {
        this.datasourceMapper = datasourceMapper;
    }

    /** 构建 MySQL 协议 jdbcUrl（可单测） */
    public static String buildJdbcUrl(String host, Integer port, String db, String extraParams) {
        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(":").append(port).append("/");
        if (db != null && !db.isEmpty()) {
            url.append(db);
        }
        url.append("?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true");
        if (extraParams != null && !extraParams.isEmpty()) {
            url.append("&").append(extraParams);
        }
        return url.toString();
    }

    /** 获取（或懒建）指定数据源的连接，调用方负责 close（归还池）。 */
    public Connection getConnection(Long datasourceId, String db) throws SQLException {
        HikariDataSource pool = pools.computeIfAbsent(poolKey(datasourceId, db), k -> createPool(datasourceId, db));
        return pool.getConnection();
    }

    private Long poolKey(Long datasourceId, String db) {
        // db 不同也复用同一数据源池（URL 已含库），简单起见用 dsId 作 key
        return datasourceId;
    }

    private HikariDataSource createPool(Long datasourceId, String db) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String pwd = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        String url = buildJdbcUrl(ds.getHost(), ds.getPort(),
                db != null ? db : ds.getDatabaseName(), ds.getExtraParams());

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(pwd);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(poolMaxSize);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10000);
        cfg.setPoolName("sync-ds-" + datasourceId);
        log.info("创建同步连接池: dsId={}, url={}", datasourceId, url);
        return new HikariDataSource(cfg);
    }

    /** 数据源配置变更/删除时调用，关闭并移除池。 */
    public void evict(Long datasourceId) {
        HikariDataSource pool = pools.remove(datasourceId);
        if (pool != null) {
            pool.close();
            log.info("关闭同步连接池: dsId={}", datasourceId);
        }
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
```

- [ ] **Step 2: 编译验证**

Run:

```powershell
mvn -q -o compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/datanote/sync/connector/ConnectionManager.java
git commit -m "feat(sync): HikariCP 连接池管理器"
```

---

## Task 6: 连接器接口与 MySQL 实现

**Files:**
- Create: `src/main/java/com/datanote/sync/connector/TableMeta.java`
- Create: `src/main/java/com/datanote/sync/connector/DbConnector.java`
- Create: `src/main/java/com/datanote/sync/connector/MysqlConnector.java`
- Test: `src/test/java/com/datanote/sync/connector/MysqlConnectorTest.java`

- [ ] **Step 1: 写表元数据类**

创建 `src/main/java/com/datanote/sync/connector/TableMeta.java`：

```java
package com.datanote.sync.connector;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 表元数据：列名（按序）+ 主键列。
 */
@Data
public class TableMeta {
    private List<String> columns = new ArrayList<>();
    private List<String> primaryKeys = new ArrayList<>();
}
```

- [ ] **Step 2: 写连接器接口**

创建 `src/main/java/com/datanote/sync/connector/DbConnector.java`：

```java
package com.datanote.sync.connector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 数据库连接器（第一版仅 MySQL 协议族实现）。
 */
public interface DbConnector {

    /** MYSQL / DORIS / STARROCKS */
    String getDatabaseType();

    /** 从连接池获取连接，调用方负责 close。 */
    Connection getConnection() throws SQLException;

    /** 获取表的列与主键。 */
    TableMeta getTableMeta(String db, String table) throws SQLException;

    /** 获取库下所有表。 */
    List<String> listTables(String db) throws SQLException;
}
```

- [ ] **Step 3: 写失败测试（纯逻辑：主键提取 + 分页 SQL）**

创建 `src/test/java/com/datanote/sync/connector/MysqlConnectorTest.java`：

```java
package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlConnectorTest {

    @Test
    void buildKeysetPageSql_firstPage_noWhere() {
        String sql = MysqlConnector.buildKeysetPageSql(
            "src_db", "t_user", Arrays.asList("id", "name"), "id", false);
        assertEquals(
            "SELECT `id`, `name` FROM `src_db`.`t_user` ORDER BY `id` ASC LIMIT ?", sql);
    }

    @Test
    void buildKeysetPageSql_nextPage_withWhere() {
        String sql = MysqlConnector.buildKeysetPageSql(
            "src_db", "t_user", Arrays.asList("id", "name"), "id", true);
        assertEquals(
            "SELECT `id`, `name` FROM `src_db`.`t_user` WHERE `id` > ? ORDER BY `id` ASC LIMIT ?", sql);
    }

    @Test
    void buildCountSql() {
        assertEquals("SELECT COUNT(*) FROM `src_db`.`t_user`",
            MysqlConnector.buildCountSql("src_db", "t_user"));
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run:

```powershell
mvn -q -o test "-Dtest=MysqlConnectorTest"
```

Expected: 编译失败（`MysqlConnector` 不存在）。

- [ ] **Step 5: 写 MySQL 连接器实现**

创建 `src/main/java/com/datanote/sync/connector/MysqlConnector.java`：

```java
package com.datanote.sync.connector;

import com.datanote.sync.util.SqlIdentifiers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL 协议族连接器（MySQL/Doris/StarRocks）。
 */
public class MysqlConnector implements DbConnector {

    private final ConnectionManager connectionManager;
    private final Long datasourceId;
    private final String defaultDb;
    private final String databaseType;

    public MysqlConnector(ConnectionManager connectionManager, Long datasourceId,
                          String defaultDb, String databaseType) {
        this.connectionManager = connectionManager;
        this.datasourceId = datasourceId;
        this.defaultDb = defaultDb;
        this.databaseType = databaseType;
    }

    @Override
    public String getDatabaseType() {
        return databaseType;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionManager.getConnection(datasourceId, defaultDb);
    }

    @Override
    public TableMeta getTableMeta(String db, String table) throws SQLException {
        TableMeta meta = new TableMeta();
        String sql = "SELECT COLUMN_NAME, COLUMN_KEY FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    meta.getColumns().add(col);
                    if ("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY"))) {
                        meta.getPrimaryKeys().add(col);
                    }
                }
            }
        }
        return meta;
    }

    @Override
    public List<String> listTables(String db) throws SQLException {
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> tables = new java.util.ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                return tables;
            }
        }
    }

    // ===== 纯逻辑：可单测的 SQL 构建 =====

    /** keyset 分页查询 SQL。firstPage=true 时无 WHERE 游标。 */
    public static String buildKeysetPageSql(String db, String table, List<String> columns,
                                            String pkColumn, boolean hasCursor) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String pk = SqlIdentifiers.quote(pkColumn);
        StringBuilder sql = new StringBuilder("SELECT ").append(cols)
                .append(" FROM ").append(fullTable);
        if (hasCursor) {
            sql.append(" WHERE ").append(pk).append(" > ?");
        }
        sql.append(" ORDER BY ").append(pk).append(" ASC LIMIT ?");
        return sql.toString();
    }

    public static String buildCountSql(String db, String table) {
        return "SELECT COUNT(*) FROM " + SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
mvn -q -o test "-Dtest=MysqlConnectorTest"
```

Expected: BUILD SUCCESS，3 个测试通过。

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/datanote/sync/connector/ src/test/java/com/datanote/sync/connector/MysqlConnectorTest.java
git commit -m "feat(sync): DbConnector 接口与 MySQL 实现"
```

---

## Task 7: 全量同步引擎

**Files:**
- Create: `src/main/java/com/datanote/sync/dto/TableSyncConfig.java`
- Create: `src/main/java/com/datanote/sync/dto/SyncContext.java`
- Create: `src/main/java/com/datanote/sync/engine/FullSyncEngine.java`

> 说明：本计划全量引擎要求源表有**单列主键**用于 keyset 分页；无主键或复合主键的支持放在计划 2。本计划假设目标表已存在（自动建表放计划 2）。

- [ ] **Step 1: 写单表配置 DTO**

创建 `src/main/java/com/datanote/sync/dto/TableSyncConfig.java`：

```java
package com.datanote.sync.dto;

import lombok.Data;

/**
 * 单表同步配置（dn_sync_job.table_config JSON 数组的元素）。
 */
@Data
public class TableSyncConfig {
    private String sourceTable;
    private String targetTable;
    private Boolean createTargetTable = Boolean.FALSE;
    private String incrementalField;   // 计划2用
    private String incrementalType;    // 计划2用：TIMESTAMP/AUTO_INCREMENT
    private String incrementalValue;   // 计划2用：断点
}
```

- [ ] **Step 2: 写同步上下文**

创建 `src/main/java/com/datanote/sync/dto/SyncContext.java`：

```java
package com.datanote.sync.dto;

import com.datanote.sync.connector.DbConnector;
import lombok.Data;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 一次同步运行的上下文与计数器。
 */
@Data
public class SyncContext {
    private Long jobId;
    private Long executionId;
    private DbConnector source;
    private DbConnector target;
    private String sourceDb;
    private String targetDb;
    private List<TableSyncConfig> tables;
    private String writeMode;
    private int batchSize = 1000;

    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 日志回调：(level, message)，由执行层接入 WebSocket。 */
    private BiConsumer<String, String> logCallback = (level, msg) -> {};

    public void log(String level, String msg) {
        logCallback.accept(level, msg);
    }
}
```

- [ ] **Step 3: 写全量引擎实现**

创建 `src/main/java/com/datanote/sync/engine/FullSyncEngine.java`：

```java
package com.datanote.sync.engine;

import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.connector.TableMeta;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.util.WriteSqlBuilder;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 全量同步引擎：按单列主键 keyset 游标分页读源表，批量 upsert 写目标表。
 */
@Slf4j
public class FullSyncEngine {

    /**
     * 执行全量同步（遍历 ctx.tables）。
     */
    public void sync(SyncContext ctx) throws Exception {
        for (TableSyncConfig tc : ctx.getTables()) {
            if (ctx.getStopped().get()) {
                ctx.log("WARN", "任务被停止，中断");
                break;
            }
            try {
                syncOneTable(ctx, tc);
            } catch (Exception e) {
                ctx.getErrorCount().incrementAndGet();
                ctx.log("ERROR", "表同步失败 " + tc.getSourceTable() + ": " + e.getMessage());
                log.error("表同步失败: {}", tc.getSourceTable(), e);
                throw e;
            }
        }
    }

    private void syncOneTable(SyncContext ctx, TableSyncConfig tc) throws Exception {
        DbConnector source = ctx.getSource();
        DbConnector target = ctx.getTarget();
        String srcDb = ctx.getSourceDb();
        String tgtDb = ctx.getTargetDb();

        TableMeta meta = source.getTableMeta(srcDb, tc.getSourceTable());
        if (meta.getColumns().isEmpty()) {
            throw new IllegalStateException("源表无列或不存在: " + tc.getSourceTable());
        }
        if (meta.getPrimaryKeys().size() != 1) {
            throw new IllegalStateException(
                "全量同步当前仅支持单列主键（计划1限制），表: " + tc.getSourceTable()
                + "，主键数=" + meta.getPrimaryKeys().size());
        }
        List<String> columns = meta.getColumns();
        String pkColumn = meta.getPrimaryKeys().get(0);
        int pkIndex = columns.indexOf(pkColumn);

        String writeSql = WriteSqlBuilder.build(ctx.getWriteMode(), tc.getTargetTable(),
                columns, meta.getPrimaryKeys());

        ctx.log("INFO", "开始全量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，列数=" + columns.size() + "，主键=" + pkColumn);

        Object cursor = null;
        boolean hasCursor = false;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);

            while (!ctx.getStopped().get()) {
                String pageSql = MysqlConnector.buildKeysetPageSql(
                        srcDb, tc.getSourceTable(), columns, pkColumn, hasCursor);

                int rowsThisPage = 0;
                try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                    int paramIdx = 1;
                    if (hasCursor) {
                        readPs.setObject(paramIdx++, cursor);
                    }
                    readPs.setInt(paramIdx, ctx.getBatchSize());

                    try (ResultSet rs = readPs.executeQuery();
                         PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                        while (rs.next()) {
                            for (int i = 0; i < columns.size(); i++) {
                                writePs.setObject(i + 1, rs.getObject(i + 1));
                            }
                            writePs.addBatch();
                            cursor = rs.getObject(pkIndex + 1);
                            rowsThisPage++;
                        }
                        if (rowsThisPage > 0) {
                            int[] res = writePs.executeBatch();
                            tgtConn.commit();
                            long written = 0;
                            for (int r : res) {
                                written += (r >= 0 ? r : 1);
                            }
                            ctx.getWriteCount().addAndGet(written);
                        }
                    }
                }

                tableRead += rowsThisPage;
                ctx.getReadCount().addAndGet(rowsThisPage);
                hasCursor = true;

                if (rowsThisPage > 0) {
                    ctx.log("INFO", tc.getSourceTable() + " 已读 " + tableRead + " 行");
                }
                if (rowsThisPage < ctx.getBatchSize()) {
                    break; // 最后一页
                }
            }
        }

        ctx.log("INFO", "完成全量同步 " + tc.getSourceTable() + "，共 " + tableRead + " 行");
    }
}
```

- [ ] **Step 4: 编译验证**

Run:

```powershell
mvn -q -o compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/datanote/sync/dto/ src/main/java/com/datanote/sync/engine/FullSyncEngine.java
git commit -m "feat(sync): 全量同步引擎（keyset 分页 + 批量写）"
```

---

## Task 8: 任务服务与执行器

**Files:**
- Create: `src/main/java/com/datanote/sync/service/SyncJobService.java`
- Create: `src/main/java/com/datanote/sync/service/SyncJobExecutor.java`

> 复用 `DnTaskExecutionMapper`（已存在，对应 `dn_task_execution`）与 `LogBroadcastService`。执行记录 `task_type='DbSync'`，`sync_task_id` 存 `dn_sync_job.id`。

- [ ] **Step 1: 写任务服务（CRUD + 构建连接器）**

创建 `src/main/java/com/datanote/sync/service/SyncJobService.java`：

```java
package com.datanote.sync.service;

import com.alibaba.fastjson2.JSON;
import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.model.DnDatasource;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.connector.ConnectionManager;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.dto.TableSyncConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 关系库同步任务管理：CRUD + 连接器构建。
 */
@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final DnSyncJobMapper syncJobMapper;
    private final DnDatasourceMapper datasourceMapper;
    private final ConnectionManager connectionManager;

    public List<DnSyncJob> list() {
        return syncJobMapper.selectList(null);
    }

    public DnSyncJob getById(Long id) {
        return syncJobMapper.selectById(id);
    }

    public DnSyncJob save(DnSyncJob job) {
        if (job.getId() != null) {
            job.setUpdatedAt(LocalDateTime.now());
            syncJobMapper.updateById(job);
        } else {
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            if (job.getStatus() == null) {
                job.setStatus("CREATED");
            }
            syncJobMapper.insert(job);
        }
        return job;
    }

    public void delete(Long id) {
        syncJobMapper.deleteById(id);
    }

    /** 解析 table_config JSON。 */
    public List<TableSyncConfig> parseTables(DnSyncJob job) {
        return JSON.parseArray(job.getTableConfig(), TableSyncConfig.class);
    }

    /** 为某数据源构建连接器（databaseType 取自 dn_datasource.type，归一为大写）。 */
    public DbConnector buildConnector(Long datasourceId, String db) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String type = ds.getType() == null ? "MYSQL" : ds.getType().toUpperCase();
        return new MysqlConnector(connectionManager, datasourceId, db, type);
    }
}
```

- [ ] **Step 2: 写执行器（建执行记录 + 调引擎 + 推日志 + 收尾）**

创建 `src/main/java/com/datanote/sync/service/SyncJobExecutor.java`：

```java
package com.datanote.sync.service;

import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.FullSyncEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 全量同步执行入口：被手动触发或调度调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobExecutor {

    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final LogBroadcastService logBroadcastService;

    private static final String TASK_TYPE = "DbSync";

    /** 执行一个同步任务（当前仅 FULL）。返回执行记录 id。 */
    public Long run(Long jobId, String triggerType) {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("同步任务不存在: " + jobId);
        }

        DnTaskExecution exec = new DnTaskExecution();
        exec.setSyncTaskId(jobId);
        exec.setTaskType(TASK_TYPE);
        exec.setTriggerType(triggerType);
        exec.setStatus("RUNNING");
        exec.setStartTime(LocalDateTime.now());
        exec.setReadCount(0L);
        exec.setWriteCount(0L);
        exec.setErrorCount(0L);
        exec.setCreatedAt(LocalDateTime.now());
        taskExecutionMapper.insert(exec);

        StringBuilder logBuf = new StringBuilder();
        SyncContext ctx = new SyncContext();
        ctx.setJobId(jobId);
        ctx.setExecutionId(exec.getId());
        ctx.setSourceDb(job.getSourceDb());
        ctx.setTargetDb(job.getTargetDb());
        ctx.setWriteMode(job.getWriteMode() == null ? "UPSERT" : job.getWriteMode());
        ctx.setBatchSize(job.getBatchSize() == null ? 1000 : job.getBatchSize());
        List<TableSyncConfig> tables = syncJobService.parseTables(job);
        ctx.setTables(tables);
        ctx.setSource(syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb()));
        ctx.setTarget(syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb()));
        ctx.setLogCallback((level, msg) -> {
            logBuf.append("[").append(level).append("] ").append(msg).append("\n");
            logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, level, msg);
        });

        String finalStatus;
        try {
            new FullSyncEngine().sync(ctx);
            finalStatus = ctx.getErrorCount().get() > 0 ? "FAILED" : "SUCCESS";
        } catch (Exception e) {
            log.error("同步任务执行失败: jobId={}", jobId, e);
            ctx.log("ERROR", "执行失败: " + e.getMessage());
            finalStatus = "FAILED";
        }

        LocalDateTime end = LocalDateTime.now();
        exec.setStatus(finalStatus);
        exec.setEndTime(end);
        exec.setDuration((int) Duration.between(exec.getStartTime(), end).getSeconds());
        exec.setReadCount(ctx.getReadCount().get());
        exec.setWriteCount(ctx.getWriteCount().get());
        exec.setErrorCount(ctx.getErrorCount().get());
        String logStr = logBuf.toString();
        exec.setLog(logStr.length() > 1_000_000 ? logStr.substring(logStr.length() - 1_000_000) : logStr);
        taskExecutionMapper.updateById(exec);

        logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, "INFO",
                "任务结束: " + finalStatus + "，读 " + ctx.getReadCount().get()
                + " 写 " + ctx.getWriteCount().get());
        return exec.getId();
    }
}
```

- [ ] **Step 3: 编译验证**

Run:

```powershell
mvn -q -o compile
```

Expected: BUILD SUCCESS。（`DnTaskExecutionMapper` 已存在于 `src/main/java/com/datanote/mapper/`，无需新建。）

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/datanote/sync/service/
git commit -m "feat(sync): 任务服务与全量执行器"
```

---

## Task 9: REST 控制器

**Files:**
- Create: `src/main/java/com/datanote/sync/controller/SyncJobController.java`

- [ ] **Step 1: 写控制器**

创建 `src/main/java/com/datanote/sync/controller/SyncJobController.java`：

```java
package com.datanote.sync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.model.R;
import com.datanote.sync.service.SyncJobExecutor;
import com.datanote.sync.service.SyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 关系库同步任务 Controller。
 */
@Slf4j
@RestController
@RequestMapping("/api/sync-job")
@Tag(name = "关系库同步", description = "关系型数据库之间的同步任务管理与执行")
@RequiredArgsConstructor
public class SyncJobController {

    private final SyncJobService syncJobService;
    private final SyncJobExecutor syncJobExecutor;
    private final DnTaskExecutionMapper taskExecutionMapper;

    @Operation(summary = "任务列表")
    @GetMapping("/list")
    public R<List<DnSyncJob>> list() {
        return R.ok(syncJobService.list());
    }

    @Operation(summary = "任务详情")
    @GetMapping("/{id}")
    public R<DnSyncJob> getById(@PathVariable Long id) {
        return R.ok(syncJobService.getById(id));
    }

    @Operation(summary = "保存任务")
    @PostMapping("/save")
    public R<DnSyncJob> save(@RequestBody DnSyncJob job) {
        return R.ok(syncJobService.save(job));
    }

    @Operation(summary = "删除任务")
    @DeleteMapping("/{id}")
    public R<String> delete(@PathVariable Long id) {
        syncJobService.delete(id);
        return R.ok("删除成功");
    }

    @Operation(summary = "手动运行（全量）")
    @PostMapping("/{id}/run")
    public R<Long> run(@PathVariable Long id) {
        try {
            Long execId = syncJobExecutor.run(id, "manual");
            return R.ok(execId);
        } catch (Exception e) {
            log.error("运行同步任务失败: id={}", id, e);
            return R.fail("运行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "执行历史")
    @GetMapping("/{id}/executions")
    public R<List<DnTaskExecution>> executions(@PathVariable Long id) {
        LambdaQueryWrapper<DnTaskExecution> wrapper = new LambdaQueryWrapper<DnTaskExecution>()
                .eq(DnTaskExecution::getSyncTaskId, id)
                .eq(DnTaskExecution::getTaskType, "DbSync")
                .orderByDesc(DnTaskExecution::getId)
                .last("LIMIT 50");
        return R.ok(taskExecutionMapper.selectList(wrapper));
    }
}
```

- [ ] **Step 2: 编译验证**

Run:

```powershell
mvn -q -o compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: 运行全部单元测试**

Run:

```powershell
mvn -q -o test "-Dtest=SqlIdentifiersTest,WriteSqlBuilderTest,MysqlConnectorTest"
```

Expected: BUILD SUCCESS，10 个测试全部通过。

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/datanote/sync/controller/SyncJobController.java
git commit -m "feat(sync): 关系库同步 REST 接口"
```

---

## Task 10: 端到端验证（真实 MySQL→MySQL 全量）

**Files:** 无（验证任务）

- [ ] **Step 1: 准备源表与目标表**

在本地 MySQL 建两个库与表（PowerShell）：

```powershell
mysql -uroot -p"<DB_PASSWORD>" -e "
CREATE DATABASE IF NOT EXISTS sync_src;
CREATE DATABASE IF NOT EXISTS sync_dst;
CREATE TABLE sync_src.t_user (id BIGINT PRIMARY KEY, name VARCHAR(50), age INT);
INSERT INTO sync_src.t_user VALUES (1,'a',10),(2,'b',20),(3,'c',30);
CREATE TABLE sync_dst.t_user (id BIGINT PRIMARY KEY, name VARCHAR(50), age INT);
"
```

- [ ] **Step 2: 启动应用**

Run:

```powershell
mvn -q -o spring-boot:run
```

Expected: 应用在 8099 启动（日志出现 `Started DataNoteApplication`）。保持运行，另开一个终端做后续步骤。

- [ ] **Step 3: 配置一个指向本地 MySQL 的数据源**

通过现有数据源接口创建（PowerShell，假设登录态见项目；若有鉴权先按现有方式登录获取 cookie/session）：

```powershell
curl.exe -s -X POST "http://localhost:8099/api/datasource/save" -H "Content-Type: application/json" -d '{\"name\":\"local-mysql\",\"type\":\"MySQL\",\"host\":\"127.0.0.1\",\"port\":3306,\"databaseName\":\"\",\"username\":\"root\",\"password\":\"<DB_PASSWORD>\"}'
```

记下返回的数据源 `id`（下面记为 `<DSID>`）。源与目标用同一个数据源实例即可（库不同）。

- [ ] **Step 4: 创建全量同步任务**

```powershell
curl.exe -s -X POST "http://localhost:8099/api/sync-job/save" -H "Content-Type: application/json" -d '{\"jobName\":\"src2dst-full\",\"sourceDsId\":<DSID>,\"targetDsId\":<DSID>,\"sourceDb\":\"sync_src\",\"targetDb\":\"sync_dst\",\"syncMode\":\"FULL\",\"writeMode\":\"UPSERT\",\"batchSize\":1000,\"tableConfig\":\"[{\\\"sourceTable\\\":\\\"t_user\\\",\\\"targetTable\\\":\\\"t_user\\\"}]\"}'
```

记下返回的任务 `id`（记为 `<JOBID>`）。

- [ ] **Step 5: 运行任务**

```powershell
curl.exe -s -X POST "http://localhost:8099/api/sync-job/<JOBID>/run"
```

Expected: 返回 `{"code":0,...,"data":<执行记录id>}`。

- [ ] **Step 6: 验证目标表数据**

```powershell
mysql -uroot -p"<DB_PASSWORD>" -e "SELECT COUNT(*) AS cnt FROM sync_dst.t_user; SELECT * FROM sync_dst.t_user ORDER BY id;"
```

Expected: `cnt = 3`，三行数据与源表一致。

- [ ] **Step 7: 验证幂等（再跑一次 UPSERT 不应重复）**

```powershell
curl.exe -s -X POST "http://localhost:8099/api/sync-job/<JOBID>/run"
mysql -uroot -p"<DB_PASSWORD>" -e "SELECT COUNT(*) AS cnt FROM sync_dst.t_user;"
```

Expected: `cnt` 仍为 `3`（UPSERT 幂等，不增行）。

- [ ] **Step 8: 验证执行历史接口**

```powershell
curl.exe -s "http://localhost:8099/api/sync-job/<JOBID>/executions"
```

Expected: 返回两条记录，`status=SUCCESS`，`readCount=3`、`writeCount` 合理。

- [ ] **Step 9: 记录验证结果到计划文档底部**

在本文件末尾追加一节"## 验证结果"，写明实际 cnt、readCount、是否幂等通过。提交：

```powershell
git add docs/superpowers/plans/2026-05-26-db-sync-plan-1-foundation-full.md
git commit -m "docs(sync): 计划1 端到端验证结果"
```

---

## 后续计划（概要，待计划 1 执行后细化）

- **计划 2（增量 + 自动建表 + Doris）**：`IncrementalSyncEngine` + 策略模式（TIMESTAMP/AUTO_INCREMENT）+ 断点回写 `table_config`；无主键/复合主键全量回退（流式 `fetchSize`）；`TypeMappingService` + `TableSchemaService` 自动建表；Doris/StarRocks 目标（Unique Key 模型）。
- **计划 3（CDC 嵌入式）**：pom 加 `debezium-embedded`/`debezium-connector-mysql` 1.9.7；`CdcSyncEngine`（消费 `SourceRecord`，复用 INSERT/UPDATE/DELETE 写入）；`JdbcOffsetBackingStore` + `JdbcSchemaHistory`（写 `dn_cdc_offset`/`dn_cdc_schema_history`）；`CdcEngineManager` 生命周期 + 重启恢复。**首步先做 Java 8 兼容性最小验证。**
- **计划 4（调度 + 前端 + 部署）**：`TaskSchedulerService` 加 `syncJob` 分支接入每日调度；前端 `view-dbsync` 视图（双数据源、多表映射、增量配置、CDC 启停、实时日志面板）；SSH 服务器核实现状 + 部署联调 + CDC 前置条件文档。

---

## Self-Review 记录

- **Spec 覆盖**：本计划覆盖 spec 第三/四/五/六节中的"连接器、连接池、全量引擎、写入策略、执行记录、WebSocket、REST"。增量/建表/CDC/调度/前端在后续计划，已在上方列出。
- **类型一致性**：`SqlIdentifiers.quote`、`WriteSqlBuilder.build(writeMode,table,columns,pkColumns)`、`MysqlConnector.buildKeysetPageSql(db,table,columns,pkColumn,hasCursor)`、`ConnectionManager.getConnection(dsId,db)`、`DbConnector.getTableMeta(db,table)`、`SyncContext` 计数器在各任务间签名一致。
- **占位符**：无 TODO/TBD；每个代码步骤含完整代码。
```
