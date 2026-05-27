# 关系库同步 · 计划 2：增量同步 + 自动建表 + Doris/StarRocks 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在计划 1（全量同步）基础上，补齐①基于时间戳/自增 ID 的真增量同步（带断点续传）、②自动建表（含 MySQL 类型→目标类型映射）、③Doris/StarRocks 作为目标库（Unique Key 模型）。

**Architecture:** 复用计划 1 的 `com.datanote.sync` 包与连接器/引擎骨架。新增 `IncrementalSyncEngine`（策略模式：TIMESTAMP/AUTO_INCREMENT）、`TableSchemaService`+`TypeMappingService`（自动建表）、`SyncEngineFactory`（按 sync_mode 选引擎）。改造 `SyncJobExecutor` 接入引擎选择、自动建表、增量断点回写。仍是纯 JDBC、零外部组件。

**Tech Stack:** Java 8、Spring Boot 2.7.18、MyBatis-Plus、HikariCP、mysql-connector-j、com.alibaba.fastjson（注意是 fastjson 不是 fastjson2）、JUnit 5。

> **设计来源：** `docs/superpowers/specs/2026-05-26-datanote-db-sync-design.md`
> **前序计划：** `docs/superpowers/plans/2026-05-26-db-sync-plan-1-foundation-full.md`（已完成）
> **环境：** 本地 Windows + PowerShell 开发编译，部署到 Linux 服务器。**只在 worktree 工作**：`D:\data\datanote\.claude\worktrees\ecstatic-ramanujan-fc4ce3`；主仓库 `D:\data\datanote` 禁止动。所有 git 用 `-C "<worktree>"`，mvn 用 `-f "<worktree>/pom.xml"`，文件用 worktree 绝对路径。
> **测试命令（PowerShell）：** `-Dtest=` 参数加引号，如 `mvn -o test -f "<worktree>/pom.xml" "-Dtest=Xxx"`。worktree target 已预热，增量编译约数秒。

## 计划 1 已落地、本计划依赖的接口（务必按此签名衔接）

- `DbConnector`：`String getDatabaseType()`、`Connection getConnection()`、`TableMeta getTableMeta(String db,String table)`、`List<String> listTables(String db)`
- `TableMeta`：`List<String> getColumns()`、`List<String> getPrimaryKeys()`
- `MysqlConnector(ConnectionManager cm, Long dsId, String defaultDb, String databaseType)`；静态 `buildKeysetPageSql(db,table,columns,pkColumn,hasCursor)`、`buildCountSql(db,table)`
- `ConnectionManager.getConnection(Long dsId, String db)`
- `WriteSqlBuilder.build(String writeMode, String db, String table, List<String> columns, List<String> pkColumns)`
- `SqlIdentifiers.quote(String)`
- `SyncContext`：getter/setter for `source/target(DbConnector)`、`sourceDb/targetDb`、`tables(List<TableSyncConfig>)`、`writeMode`、`batchSize`；`getReadCount()/getWriteCount()/getErrorCount()(AtomicLong)`、`getStopped()(AtomicBoolean)`、`log(level,msg)`
- `TableSyncConfig`：`sourceTable/targetTable/createTargetTable/incrementalField/incrementalType/incrementalValue`
- `FullSyncEngine`：`void sync(SyncContext ctx)`（plain class）
- `SyncJobService`：`getById/list/save/delete`、`parseTables(DnSyncJob)`、`buildConnector(Long dsId,String db)`
- `SyncJobExecutor`：`Long run(Long jobId, String triggerType)`（当前直接 `new FullSyncEngine().sync(ctx)`）
- `DnSyncJob`：含 `syncMode`、`tableConfig`、`writeMode` 等字段

---

## 文件结构

| 文件 | 职责 | 新建/修改 |
|------|------|----------|
| `sync/connector/ColumnDef.java` | 列完整定义（名/类型/可空/主键/注释），自动建表用 | 新建 |
| `sync/connector/DbConnector.java` | 接口加 `getColumnDefs(db,table)` | 修改 |
| `sync/connector/MysqlConnector.java` | 实现 `getColumnDefs`；加静态 `buildIncrementalPageSql` | 修改 |
| `sync/schema/TypeMappingService.java` | MySQL 列类型 → Doris 类型 | 新建 |
| `sync/schema/TableSchemaService.java` | 生成并执行目标建表 DDL（MySQL/Doris） | 新建 |
| `sync/engine/SyncEngine.java` | 统一引擎接口 `void sync(SyncContext)` | 新建 |
| `sync/engine/FullSyncEngine.java` | 加 `implements SyncEngine` | 修改 |
| `sync/engine/incremental/IncrementalStrategy.java` | 增量策略接口 | 新建 |
| `sync/engine/incremental/TimestampStrategy.java` | 时间戳策略 | 新建 |
| `sync/engine/incremental/AutoIncrementStrategy.java` | 自增 ID 策略 | 新建 |
| `sync/engine/incremental/IncrementalStrategyFactory.java` | 按 type 选策略 | 新建 |
| `sync/engine/IncrementalSyncEngine.java` | 增量引擎 + 断点跟踪 | 新建 |
| `sync/engine/SyncEngineFactory.java` | 按 sync_mode 选引擎 | 新建 |
| `sync/service/SyncJobExecutor.java` | 接入引擎选择 + 自动建表 + 断点回写 | 修改 |
| 对应 `src/test/...` | TypeMapping/TableSchema/策略/增量分页 SQL 的单测 | 新建 |

---

## A 部分：自动建表 + 类型映射 + Doris 目标

### Task 1: ColumnDef 与连接器列定义查询

**Files:**
- Create: `src/main/java/com/datanote/sync/connector/ColumnDef.java`
- Modify: `src/main/java/com/datanote/sync/connector/DbConnector.java`
- Modify: `src/main/java/com/datanote/sync/connector/MysqlConnector.java`

- [ ] **Step 1: 创建 ColumnDef**

`src/main/java/com/datanote/sync/connector/ColumnDef.java`：

```java
package com.datanote.sync.connector;

import lombok.Data;

/**
 * 列完整定义（用于自动建表）。
 */
@Data
public class ColumnDef {
    private String name;         // 列名
    private String columnType;   // MySQL 原始类型，如 varchar(50)/int/datetime/decimal(10,2)
    private boolean nullable;    // 是否可空
    private boolean primaryKey;  // 是否主键
    private String comment;      // 注释
}
```

- [ ] **Step 2: DbConnector 接口加方法**

在 `src/main/java/com/datanote/sync/connector/DbConnector.java` 的接口里，`listTables` 方法后面加一个方法（其余不动）：

```java
    /** 获取表的完整列定义（用于自动建表）。 */
    java.util.List<ColumnDef> getColumnDefs(String db, String table) throws java.sql.SQLException;
```

- [ ] **Step 3: MysqlConnector 实现 getColumnDefs**

在 `MysqlConnector.java` 中，`listTables` 方法之后、`// ===== 纯逻辑` 注释之前，加入实现：

```java
    @Override
    public java.util.List<ColumnDef> getColumnDefs(String db, String table) throws java.sql.SQLException {
        java.util.List<ColumnDef> list = new java.util.ArrayList<>();
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION";
        try (java.sql.Connection conn = getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnDef col = new ColumnDef();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setColumnType(rs.getString("COLUMN_TYPE"));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    col.setPrimaryKey("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")));
                    col.setComment(rs.getString("COLUMN_COMMENT"));
                    list.add(col);
                }
            }
        }
        return list;
    }
```

- [ ] **Step 4: 编译**

Run: `mvn -o compile -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml"`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/connector/ColumnDef.java src/main/java/com/datanote/sync/connector/DbConnector.java src/main/java/com/datanote/sync/connector/MysqlConnector.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): 连接器支持查询列完整定义 ColumnDef"
```

---

### Task 2: 类型映射服务（TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/schema/TypeMappingService.java`
- Test: `src/test/java/com/datanote/sync/schema/TypeMappingServiceTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/datanote/sync/schema/TypeMappingServiceTest.java`：

```java
package com.datanote.sync.schema;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeMappingServiceTest {

    private final TypeMappingService svc = new TypeMappingService();

    @Test
    void mysqlToDoris_integers() {
        assertEquals("TINYINT", svc.mysqlToDoris("tinyint"));
        assertEquals("INT", svc.mysqlToDoris("int(11)"));
        assertEquals("BIGINT", svc.mysqlToDoris("bigint(20) unsigned"));
    }

    @Test
    void mysqlToDoris_stringsAndText() {
        assertEquals("VARCHAR(150)", svc.mysqlToDoris("varchar(50)")); // 字节预留：长度*3
        assertEquals("STRING", svc.mysqlToDoris("text"));
        assertEquals("STRING", svc.mysqlToDoris("longtext"));
    }

    @Test
    void mysqlToDoris_dateAndDecimal() {
        assertEquals("DATETIME", svc.mysqlToDoris("datetime"));
        assertEquals("DATE", svc.mysqlToDoris("date"));
        assertEquals("DECIMAL(10,2)", svc.mysqlToDoris("decimal(10,2)"));
    }

    @Test
    void mysqlToDoris_unknownFallsBackToString() {
        assertEquals("STRING", svc.mysqlToDoris("geometry"));
        assertEquals("STRING", svc.mysqlToDoris("json"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=TypeMappingServiceTest"`
Expected: 编译失败（TypeMappingService 不存在）。

- [ ] **Step 3: 写实现**

`src/main/java/com/datanote/sync/schema/TypeMappingService.java`：

```java
package com.datanote.sync.schema;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 列类型 → 目标库类型映射。第一版支持 MySQL→Doris/StarRocks。
 * MySQL→MySQL 直接照搬源类型，无需经过本服务。
 */
@Service
public class TypeMappingService {

    private static final Pattern LEN = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern DECIMAL = Pattern.compile("decimal\\s*\\((\\d+)\\s*,\\s*(\\d+)\\)");

    /**
     * 把 MySQL 的 COLUMN_TYPE（如 varchar(50)、int(11) unsigned、decimal(10,2)）映射为 Doris 类型。
     */
    public String mysqlToDoris(String mysqlColumnType) {
        if (mysqlColumnType == null) {
            return "STRING";
        }
        String t = mysqlColumnType.trim().toLowerCase();

        if (t.startsWith("tinyint")) return "TINYINT";
        if (t.startsWith("smallint")) return "SMALLINT";
        if (t.startsWith("mediumint")) return "INT";
        if (t.startsWith("bigint")) return "BIGINT";
        if (t.startsWith("int") || t.startsWith("integer")) return "INT";
        if (t.startsWith("float")) return "FLOAT";
        if (t.startsWith("double")) return "DOUBLE";
        if (t.startsWith("decimal") || t.startsWith("numeric")) {
            Matcher m = DECIMAL.matcher(t);
            if (m.find()) {
                return "DECIMAL(" + m.group(1) + "," + m.group(2) + ")";
            }
            return "DECIMAL(10,0)";
        }
        if (t.startsWith("datetime") || t.startsWith("timestamp")) return "DATETIME";
        if (t.startsWith("date")) return "DATE";
        if (t.startsWith("char")) {
            return "CHAR(" + lenOrDefault(t, 1) + ")";
        }
        if (t.startsWith("varchar")) {
            // Doris VARCHAR 按字节，中文需预留，长度 *3
            return "VARCHAR(" + (lenOrDefault(t, 255) * 3) + ")";
        }
        // text/longtext/mediumtext/tinytext/blob/json/枚举/几何等统一 STRING
        return "STRING";
    }

    private int lenOrDefault(String type, int def) {
        Matcher m = LEN.matcher(type);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return def;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=TypeMappingServiceTest"`
Expected: Tests run: 4, Failures: 0。

- [ ] **Step 5: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/schema/TypeMappingService.java src/test/java/com/datanote/sync/schema/TypeMappingServiceTest.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): MySQL→Doris 类型映射服务"
```

---

### Task 3: 建表 DDL 生成服务（TDD + 集成）

**Files:**
- Create: `src/main/java/com/datanote/sync/schema/TableSchemaService.java`
- Test: `src/test/java/com/datanote/sync/schema/TableSchemaServiceTest.java`

> DDL 生成是纯逻辑，走 TDD（静态方法 `buildDdl`）；执行建表 `ensureTargetTable` 是集成（依赖连接器），仅编译验证。

- [ ] **Step 1: 写失败测试（只测 DDL 字符串生成）**

`src/test/java/com/datanote/sync/schema/TableSchemaServiceTest.java`：

```java
package com.datanote.sync.schema;

import com.datanote.sync.connector.ColumnDef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSchemaServiceTest {

    private final TableSchemaService svc = new TableSchemaService(new TypeMappingService());

    private List<ColumnDef> cols() {
        ColumnDef id = new ColumnDef();
        id.setName("id"); id.setColumnType("bigint"); id.setNullable(false); id.setPrimaryKey(true); id.setComment("主键");
        ColumnDef name = new ColumnDef();
        name.setName("name"); name.setColumnType("varchar(50)"); name.setNullable(true); name.setPrimaryKey(false); name.setComment("");
        return Arrays.asList(id, name);
    }

    @Test
    void mysqlDdl_copiesSourceTypes_withPrimaryKey() {
        String ddl = svc.buildDdl("MYSQL", "dst", "t_user", cols());
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `dst`.`t_user` (\n"
            + "  `id` bigint NOT NULL COMMENT '主键',\n"
            + "  `name` varchar(50) NULL,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            ddl);
    }

    @Test
    void dorisDdl_usesUniqueKeyModel() {
        String ddl = svc.buildDdl("DORIS", "dst", "t_user", cols());
        assertTrue(ddl.contains("`id` BIGINT"), ddl);
        assertTrue(ddl.contains("`name` VARCHAR(150)"), ddl);
        assertTrue(ddl.contains("UNIQUE KEY(`id`)"), ddl);
        assertTrue(ddl.contains("DISTRIBUTED BY HASH(`id`) BUCKETS 10"), ddl);
        assertTrue(ddl.contains("\"replication_num\" = \"1\""), ddl);
    }

    @Test
    void noPrimaryKey_throws() {
        ColumnDef c = new ColumnDef();
        c.setName("v"); c.setColumnType("int"); c.setNullable(true); c.setPrimaryKey(false);
        try {
            svc.buildDdl("DORIS", "dst", "t", Collections.singletonList(c));
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=TableSchemaServiceTest"`
Expected: 编译失败（TableSchemaService 不存在）。

- [ ] **Step 3: 写实现**

`src/main/java/com/datanote/sync/schema/TableSchemaService.java`：

```java
package com.datanote.sync.schema;

import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.util.SqlIdentifiers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 目标表自动建表：生成 DDL（MySQL 照搬源类型 / Doris 用 Unique Key 模型）并执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableSchemaService {

    private final TypeMappingService typeMappingService;

    /**
     * 生成建表 DDL。
     * @param targetType MYSQL / DORIS / STARROCKS
     */
    public String buildDdl(String targetType, String db, String table, List<ColumnDef> columns) {
        List<String> pks = columns.stream().filter(ColumnDef::isPrimaryKey)
                .map(ColumnDef::getName).collect(Collectors.toList());
        if (pks.isEmpty()) {
            throw new IllegalStateException("源表无主键，无法自动建表: " + table);
        }
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        boolean doris = "DORIS".equalsIgnoreCase(targetType) || "STARROCKS".equalsIgnoreCase(targetType);

        List<String> colLines = new ArrayList<>();
        if (doris) {
            // Doris Unique Key 模型要求 key 列（主键）排在最前
            List<ColumnDef> ordered = new ArrayList<>();
            columns.stream().filter(ColumnDef::isPrimaryKey).forEach(ordered::add);
            columns.stream().filter(c -> !c.isPrimaryKey()).forEach(ordered::add);
            for (ColumnDef c : ordered) {
                colLines.add("  " + SqlIdentifiers.quote(c.getName()) + " "
                        + typeMappingService.mysqlToDoris(c.getColumnType())
                        + comment(c.getComment()));
            }
            String pkList = pks.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
            return "CREATE TABLE IF NOT EXISTS " + fullTable + " (\n"
                    + String.join(",\n", colLines) + "\n"
                    + ") UNIQUE KEY(" + pkList + ")\n"
                    + "DISTRIBUTED BY HASH(" + pkList + ") BUCKETS 10\n"
                    + "PROPERTIES (\"replication_num\" = \"1\")";
        } else {
            // MySQL：照搬源列类型
            for (ColumnDef c : columns) {
                colLines.add("  " + SqlIdentifiers.quote(c.getName()) + " " + c.getColumnType()
                        + (c.isNullable() ? " NULL" : " NOT NULL")
                        + comment(c.getComment()));
            }
            String pkList = pks.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
            return "CREATE TABLE IF NOT EXISTS " + fullTable + " (\n"
                    + String.join(",\n", colLines) + ",\n"
                    + "  PRIMARY KEY (" + pkList + ")\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        }
    }

    private String comment(String c) {
        if (c == null || c.isEmpty()) {
            return "";
        }
        return " COMMENT '" + c.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * 若目标表不存在则建表。存在则跳过。
     */
    public void ensureTargetTable(DbConnector target, String targetDb, String targetTable,
                                  List<ColumnDef> sourceColumns) throws SQLException {
        List<String> existing = target.listTables(targetDb);
        if (existing.contains(targetTable)) {
            log.info("目标表已存在，跳过建表: {}.{}", targetDb, targetTable);
            return;
        }
        String ddl = buildDdl(target.getDatabaseType(), targetDb, targetTable, sourceColumns);
        log.info("自动建表 DDL:\n{}", ddl);
        try (Connection conn = target.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(ddl);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=TableSchemaServiceTest"`
Expected: Tests run: 3, Failures: 0。

- [ ] **Step 5: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/schema/TableSchemaService.java src/test/java/com/datanote/sync/schema/TableSchemaServiceTest.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): 自动建表 DDL 生成服务（MySQL/Doris）"
```

---

## B 部分：增量同步

### Task 4: 统一引擎接口 + 增量策略（TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/engine/SyncEngine.java`
- Modify: `src/main/java/com/datanote/sync/engine/FullSyncEngine.java`（加 implements）
- Create: `src/main/java/com/datanote/sync/engine/incremental/IncrementalStrategy.java`
- Create: `src/main/java/com/datanote/sync/engine/incremental/AutoIncrementStrategy.java`
- Create: `src/main/java/com/datanote/sync/engine/incremental/TimestampStrategy.java`
- Create: `src/main/java/com/datanote/sync/engine/incremental/IncrementalStrategyFactory.java`
- Test: `src/test/java/com/datanote/sync/engine/incremental/IncrementalStrategyTest.java`

- [ ] **Step 1: 统一引擎接口**

`src/main/java/com/datanote/sync/engine/SyncEngine.java`：

```java
package com.datanote.sync.engine;

import com.datanote.sync.dto.SyncContext;

/**
 * 同步引擎统一接口。
 */
public interface SyncEngine {
    void sync(SyncContext ctx);
}
```

- [ ] **Step 2: FullSyncEngine 实现接口**

把 `FullSyncEngine` 类声明从 `public class FullSyncEngine {` 改为 `public class FullSyncEngine implements SyncEngine {`，并在 `sync` 方法上加 `@Override`。其余不变（`sync(SyncContext)` 签名已匹配）。

- [ ] **Step 3: 写失败测试（策略比较逻辑）**

`src/test/java/com/datanote/sync/engine/incremental/IncrementalStrategyTest.java`：

```java
package com.datanote.sync.engine.incremental;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalStrategyTest {

    @Test
    void autoIncrement_comparesNumerically() {
        IncrementalStrategy s = new AutoIncrementStrategy();
        assertEquals("AUTO_INCREMENT", s.type());
        assertTrue(s.compare("100", "20") > 0);   // 100 > 20（数值比较，非字符串）
        assertTrue(s.compare("5", "5") == 0);
        assertEquals("42", s.toStored(42L));
    }

    @Test
    void timestamp_comparesAsString() {
        IncrementalStrategy s = new TimestampStrategy();
        assertEquals("TIMESTAMP", s.type());
        assertTrue(s.compare("2026-01-02 00:00:00", "2026-01-01 23:59:59") > 0);
        assertEquals("2026-01-01 10:00:00", s.toStored("2026-01-01 10:00:00"));
    }

    @Test
    void factory_returnsByType() {
        assertTrue(IncrementalStrategyFactory.get("AUTO_INCREMENT") instanceof AutoIncrementStrategy);
        assertTrue(IncrementalStrategyFactory.get("TIMESTAMP") instanceof TimestampStrategy);
        assertTrue(IncrementalStrategyFactory.get(null) instanceof TimestampStrategy); // 默认时间戳
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=IncrementalStrategyTest"`
Expected: 编译失败。

- [ ] **Step 5: 写策略实现**

`src/main/java/com/datanote/sync/engine/incremental/IncrementalStrategy.java`：

```java
package com.datanote.sync.engine.incremental;

/**
 * 增量策略：决定增量值如何比较与持久化。增量查询用 PreparedStatement 参数化，无需拼值。
 */
public interface IncrementalStrategy {

    String type();

    /** 比较两个增量值（用于求本批最大断点）。a>b 返回正，相等 0，a<b 负。 */
    int compare(Object a, Object b);

    /** 把增量值转为持久化字符串（写回 table_config.incrementalValue）。 */
    String toStored(Object value);
}
```

`src/main/java/com/datanote/sync/engine/incremental/AutoIncrementStrategy.java`：

```java
package com.datanote.sync.engine.incremental;

import java.math.BigDecimal;

/**
 * 自增 ID 增量策略：按数值比较。
 */
public class AutoIncrementStrategy implements IncrementalStrategy {

    @Override
    public String type() {
        return "AUTO_INCREMENT";
    }

    @Override
    public int compare(Object a, Object b) {
        return new BigDecimal(String.valueOf(a)).compareTo(new BigDecimal(String.valueOf(b)));
    }

    @Override
    public String toStored(Object value) {
        return String.valueOf(value);
    }
}
```

`src/main/java/com/datanote/sync/engine/incremental/TimestampStrategy.java`：

```java
package com.datanote.sync.engine.incremental;

/**
 * 时间戳增量策略：按字符串字典序比较（标准时间格式 yyyy-MM-dd HH:mm:ss 字典序与时间序一致）。
 */
public class TimestampStrategy implements IncrementalStrategy {

    @Override
    public String type() {
        return "TIMESTAMP";
    }

    @Override
    public int compare(Object a, Object b) {
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    @Override
    public String toStored(Object value) {
        return String.valueOf(value);
    }
}
```

`src/main/java/com/datanote/sync/engine/incremental/IncrementalStrategyFactory.java`：

```java
package com.datanote.sync.engine.incremental;

/**
 * 增量策略工厂。
 */
public final class IncrementalStrategyFactory {

    private IncrementalStrategyFactory() {
    }

    public static IncrementalStrategy get(String type) {
        if ("AUTO_INCREMENT".equalsIgnoreCase(type)) {
            return new AutoIncrementStrategy();
        }
        return new TimestampStrategy(); // 默认时间戳
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=IncrementalStrategyTest"`
Expected: Tests run: 3, Failures: 0。

- [ ] **Step 7: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/engine/SyncEngine.java src/main/java/com/datanote/sync/engine/FullSyncEngine.java src/main/java/com/datanote/sync/engine/incremental/ src/test/java/com/datanote/sync/engine/incremental/IncrementalStrategyTest.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): 统一引擎接口与增量策略（时间戳/自增ID）"
```

---

### Task 5: 增量分页 SQL（TDD）

**Files:**
- Modify: `src/main/java/com/datanote/sync/connector/MysqlConnector.java`
- Test: `src/test/java/com/datanote/sync/connector/MysqlConnectorIncrementalTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/datanote/sync/connector/MysqlConnectorIncrementalTest.java`：

```java
package com.datanote.sync.connector;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlConnectorIncrementalTest {

    @Test
    void buildIncrementalPageSql_filtersAndOrdersByIncrementalField() {
        String sql = MysqlConnector.buildIncrementalPageSql(
            "src_db", "t_order", Arrays.asList("id", "amount", "updated_at"), "updated_at");
        assertEquals(
            "SELECT `id`, `amount`, `updated_at` FROM `src_db`.`t_order` "
            + "WHERE `updated_at` > ? ORDER BY `updated_at` ASC LIMIT ?", sql);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=MysqlConnectorIncrementalTest"`
Expected: 编译失败（buildIncrementalPageSql 不存在）。

- [ ] **Step 3: 在 MysqlConnector 加静态方法**

在 `MysqlConnector.java` 的 `buildCountSql` 方法之后加入：

```java
    /** 增量分页查询 SQL：WHERE incField > ? ORDER BY incField ASC LIMIT ?（游标即 incField 值）。 */
    public static String buildIncrementalPageSql(String db, String table, List<String> columns, String incField) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String inc = SqlIdentifiers.quote(incField);
        return "SELECT " + cols + " FROM " + fullTable
                + " WHERE " + inc + " > ? ORDER BY " + inc + " ASC LIMIT ?";
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=MysqlConnectorIncrementalTest"`
Expected: Tests run: 1, Failures: 0。

- [ ] **Step 5: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/connector/MysqlConnector.java src/test/java/com/datanote/sync/connector/MysqlConnectorIncrementalTest.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): 增量分页 SQL 构建"
```

---

### Task 6: 增量同步引擎

**Files:**
- Create: `src/main/java/com/datanote/sync/engine/IncrementalSyncEngine.java`

> 增量引擎按 `incrementalField` 升序分页（游标=该字段值，初值=断点 `incrementalValue`），批量 upsert 写目标，跟踪本次最大值并回写到 `TableSyncConfig.incrementalValue`（内存对象，由执行层持久化）。首次无断点时用 `''`（空串，配合 `> ?` 取全部，等价首次全量）；自增 ID 场景断点初值建议为 `0`。仅编译验证，逻辑正确性留待 Task 9 真实库验证。

- [ ] **Step 1: 写实现**

`src/main/java/com/datanote/sync/engine/IncrementalSyncEngine.java`：

```java
package com.datanote.sync.engine;

import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.connector.MysqlConnector;
import com.datanote.sync.connector.TableMeta;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.incremental.IncrementalStrategy;
import com.datanote.sync.engine.incremental.IncrementalStrategyFactory;
import com.datanote.sync.util.WriteSqlBuilder;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 增量同步引擎：按 incrementalField 升序分页（游标=该字段值，初值=断点），批量 upsert 写目标。
 * 每表跟踪本次最大增量值并写回 TableSyncConfig.incrementalValue，由执行层持久化为断点。
 */
@Slf4j
public class IncrementalSyncEngine implements SyncEngine {

    @Override
    public void sync(SyncContext ctx) {
        for (TableSyncConfig tc : ctx.getTables()) {
            if (ctx.getStopped().get()) {
                ctx.log("WARN", "任务被停止，中断");
                break;
            }
            try {
                syncOneTable(ctx, tc);
            } catch (Exception e) {
                ctx.getErrorCount().incrementAndGet();
                ctx.log("ERROR", "表增量同步失败 " + tc.getSourceTable() + ": " + e.getMessage());
                log.error("表增量同步失败: {}", tc.getSourceTable(), e);
            }
        }
    }

    private void syncOneTable(SyncContext ctx, TableSyncConfig tc) throws Exception {
        DbConnector source = ctx.getSource();
        DbConnector target = ctx.getTarget();
        String srcDb = ctx.getSourceDb();
        String tgtDb = ctx.getTargetDb();

        if (tc.getIncrementalField() == null || tc.getIncrementalField().trim().isEmpty()) {
            throw new IllegalStateException("增量同步未配置 incrementalField，表: " + tc.getSourceTable());
        }
        String incField = tc.getIncrementalField();

        TableMeta meta = source.getTableMeta(srcDb, tc.getSourceTable());
        if (meta.getColumns().isEmpty()) {
            throw new IllegalStateException("源表无列或不存在: " + tc.getSourceTable());
        }
        if (!meta.getColumns().contains(incField)) {
            throw new IllegalStateException("增量字段不在源表列中: " + incField);
        }
        List<String> columns = meta.getColumns();
        int incIndex = columns.indexOf(incField);

        IncrementalStrategy strategy = IncrementalStrategyFactory.get(tc.getIncrementalType());

        // 断点初值：有则用，无则空串（时间戳/字符串）或 0（自增）
        Object lastValue = tc.getIncrementalValue();
        if (lastValue == null || String.valueOf(lastValue).isEmpty()) {
            lastValue = "AUTO_INCREMENT".equalsIgnoreCase(tc.getIncrementalType()) ? "0" : "";
        }

        String writeSql = WriteSqlBuilder.build(ctx.getWriteMode(), tgtDb, tc.getTargetTable(),
                columns, meta.getPrimaryKeys());
        String pageSql = MysqlConnector.buildIncrementalPageSql(srcDb, tc.getSourceTable(), columns, incField);

        ctx.log("INFO", "开始增量同步 " + tc.getSourceTable() + " -> " + tc.getTargetTable()
                + "，增量字段=" + incField + "，起始断点=" + lastValue);

        Object maxValue = lastValue;
        long tableRead = 0;

        try (Connection srcConn = source.getConnection();
             Connection tgtConn = target.getConnection()) {
            tgtConn.setAutoCommit(false);

            while (!ctx.getStopped().get()) {
                int rowsThisPage = 0;
                try (PreparedStatement readPs = srcConn.prepareStatement(pageSql)) {
                    readPs.setObject(1, maxValue);
                    readPs.setInt(2, ctx.getBatchSize());

                    try (ResultSet rs = readPs.executeQuery();
                         PreparedStatement writePs = tgtConn.prepareStatement(writeSql)) {
                        Object pageMax = maxValue;
                        while (rs.next()) {
                            for (int i = 0; i < columns.size(); i++) {
                                writePs.setObject(i + 1, rs.getObject(i + 1));
                            }
                            writePs.addBatch();
                            Object cur = rs.getObject(incIndex + 1);
                            if (cur != null && strategy.compare(cur, pageMax) > 0) {
                                pageMax = cur;
                            }
                            rowsThisPage++;
                        }
                        if (rowsThisPage > 0) {
                            try {
                                writePs.executeBatch();
                                tgtConn.commit();
                            } catch (Exception batchEx) {
                                tgtConn.rollback();
                                throw batchEx;
                            }
                            ctx.getWriteCount().addAndGet(rowsThisPage);
                            maxValue = pageMax;
                        }
                    }
                }

                tableRead += rowsThisPage;
                ctx.getReadCount().addAndGet(rowsThisPage);

                if (rowsThisPage < ctx.getBatchSize()) {
                    break;
                }
            }
        }

        // 回写断点（内存对象，执行层持久化）
        tc.setIncrementalValue(strategy.toStored(maxValue));
        ctx.log("INFO", "完成增量同步 " + tc.getSourceTable() + "，本次 " + tableRead
                + " 行，新断点=" + tc.getIncrementalValue());
    }
}
```

- [ ] **Step 2: 编译**

Run: `mvn -o compile -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml"`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/engine/IncrementalSyncEngine.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): 增量同步引擎（断点续传）"
```

---

### Task 7: 引擎工厂 + 执行器接入（建表 + 选引擎 + 断点回写）

**Files:**
- Create: `src/main/java/com/datanote/sync/engine/SyncEngineFactory.java`
- Modify: `src/main/java/com/datanote/sync/service/SyncJobService.java`（加 `updateTableConfig`）
- Modify: `src/main/java/com/datanote/sync/service/SyncJobExecutor.java`

- [ ] **Step 1: 引擎工厂**

`src/main/java/com/datanote/sync/engine/SyncEngineFactory.java`：

```java
package com.datanote.sync.engine;

/**
 * 按 syncMode 选择同步引擎。
 */
public final class SyncEngineFactory {

    private SyncEngineFactory() {
    }

    public static SyncEngine get(String syncMode) {
        if ("INCREMENTAL".equalsIgnoreCase(syncMode)) {
            return new IncrementalSyncEngine();
        }
        return new FullSyncEngine(); // 默认全量（CDC 由独立 Manager 处理，不走此工厂）
    }
}
```

- [ ] **Step 2: SyncJobService 加断点持久化方法**

在 `SyncJobService` 中加入（需要 import `com.alibaba.fastjson.JSON`、`com.datanote.sync.dto.TableSyncConfig`、`java.util.List`，已有的不重复）：

```java
    /** 把内存中的表配置（含更新后的增量断点）序列化写回 dn_sync_job.tableConfig。 */
    public void updateTableConfig(Long jobId, java.util.List<com.datanote.sync.dto.TableSyncConfig> tables) {
        com.datanote.model.DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        job.setTableConfig(com.alibaba.fastjson.JSON.toJSONString(tables));
        job.setUpdatedAt(java.time.LocalDateTime.now());
        syncJobMapper.updateById(job);
    }
```

- [ ] **Step 3: 改造 SyncJobExecutor**

修改 `SyncJobExecutor`：①注入 `TableSchemaService`；②执行前按 `createTargetTable` 自动建表；③用 `SyncEngineFactory` 按 `job.getSyncMode()` 选引擎；④执行后若为增量模式，回写断点。

完整替换 `SyncJobExecutor.java` 为：

```java
package com.datanote.sync.service;

import com.datanote.mapper.DnTaskExecutionMapper;
import com.datanote.model.DnSyncJob;
import com.datanote.model.DnTaskExecution;
import com.datanote.service.LogBroadcastService;
import com.datanote.sync.connector.ColumnDef;
import com.datanote.sync.connector.DbConnector;
import com.datanote.sync.dto.SyncContext;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.engine.SyncEngine;
import com.datanote.sync.engine.SyncEngineFactory;
import com.datanote.sync.schema.TableSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步执行入口：被手动触发或调度调用。支持 FULL/INCREMENTAL，自动建表，增量断点回写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobExecutor {

    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;
    private final LogBroadcastService logBroadcastService;
    private final TableSchemaService tableSchemaService;

    private static final String TASK_TYPE = "DbSync";
    private static final int MAX_LOG = 1_000_000;

    /** 执行一个同步任务。返回执行记录 id。 */
    public Long run(Long jobId, String triggerType) {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("同步任务不存在: " + jobId);
        }
        if (job.getSourceDsId() == null || job.getTargetDsId() == null) {
            throw new IllegalArgumentException("sourceDsId 和 targetDsId 不能为空");
        }
        if (isBlank(job.getSourceDb()) || isBlank(job.getTargetDb())) {
            throw new IllegalArgumentException("sourceDb 和 targetDb 不能为空");
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
        String syncMode = job.getSyncMode() == null ? "FULL" : job.getSyncMode().toUpperCase();

        SyncContext ctx = new SyncContext();
        ctx.setJobId(jobId);
        ctx.setExecutionId(exec.getId());
        ctx.setSourceDb(job.getSourceDb());
        ctx.setTargetDb(job.getTargetDb());
        ctx.setWriteMode(job.getWriteMode() == null ? "UPSERT" : job.getWriteMode());
        ctx.setBatchSize(job.getBatchSize() == null ? 1000 : job.getBatchSize());
        List<TableSyncConfig> tables = syncJobService.parseTables(job);
        ctx.setTables(tables);
        DbConnector source = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector target = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        ctx.setSource(source);
        ctx.setTarget(target);
        ctx.setLogCallback((level, msg) -> {
            logBuf.append("[").append(level).append("] ").append(msg).append("\n");
            logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, level, msg);
        });

        String finalStatus;
        try {
            // 自动建表（按需）
            for (TableSyncConfig tc : tables) {
                if (Boolean.TRUE.equals(tc.getCreateTargetTable())) {
                    List<ColumnDef> cols = source.getColumnDefs(job.getSourceDb(), tc.getSourceTable());
                    tableSchemaService.ensureTargetTable(target, job.getTargetDb(), tc.getTargetTable(), cols);
                    ctx.log("INFO", "目标表就绪: " + tc.getTargetTable());
                }
            }

            SyncEngine engine = SyncEngineFactory.get(syncMode);
            engine.sync(ctx);
            finalStatus = ctx.getErrorCount().get() > 0 ? "FAILED" : "SUCCESS";

            // 增量模式：回写断点（仅在无失败时持久化，避免错误断点跳过数据）
            if ("INCREMENTAL".equals(syncMode) && ctx.getErrorCount().get() == 0) {
                syncJobService.updateTableConfig(jobId, tables);
            }
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
        if (logStr.length() > MAX_LOG) {
            logStr = "[...日志过长，已截断前段...]\n" + logStr.substring(logStr.length() - MAX_LOG);
        }
        exec.setLog(logStr);
        taskExecutionMapper.updateById(exec);

        logBroadcastService.broadcastTaskLog(jobId, TASK_TYPE, "INFO",
                "任务结束: " + finalStatus + "，读 " + ctx.getReadCount().get()
                + " 写 " + ctx.getWriteCount().get());
        return exec.getId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
```

- [ ] **Step 4: 编译 + 整体单测回归**

Run: `mvn -o test -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml" "-Dtest=SqlIdentifiersTest,WriteSqlBuilderTest,MysqlConnectorTest,MysqlConnectorIncrementalTest,TypeMappingServiceTest,TableSchemaServiceTest,IncrementalStrategyTest"`
Expected: BUILD SUCCESS，全部测试通过（10 + 1 + 4 + 3 + 3 = 21）。

- [ ] **Step 5: Commit**

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add src/main/java/com/datanote/sync/engine/SyncEngineFactory.java src/main/java/com/datanote/sync/service/SyncJobService.java src/main/java/com/datanote/sync/service/SyncJobExecutor.java
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "feat(sync): 引擎工厂 + 执行器接入自动建表与增量断点回写"
```

---

### Task 8: 端到端验证（增量 + 自动建表 + Doris，真实库）

**Files:** 无（验证任务，需真实 MySQL；Doris 若无环境可只验证 MySQL→MySQL 增量+建表）

- [ ] **Step 1: 准备源表（带增量字段）**

```powershell
mysql -uroot -p"<DB_PASSWORD>" -e "
DROP TABLE IF EXISTS sync_src.t_order;
CREATE TABLE sync_src.t_order (id BIGINT PRIMARY KEY, amount INT, updated_at DATETIME, name VARCHAR(50) COMMENT '名称');
INSERT INTO sync_src.t_order VALUES (1,100,'2026-01-01 10:00:00','a'),(2,200,'2026-01-02 10:00:00','b');
DROP TABLE IF EXISTS sync_dst.t_order;
"
```

- [ ] **Step 2: 启动应用**

Run: `mvn -q -o spring-boot:run -f "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3/pom.xml"`（另开终端做后续）

- [ ] **Step 3: 建增量任务（自动建表 + 时间戳增量）**

用 Task 10（计划1）已配置的数据源 `<DSID>`：

```powershell
curl.exe -s -X POST "http://localhost:8099/api/sync-job/save" -H "Content-Type: application/json" -d '{\"jobName\":\"order-incr\",\"sourceDsId\":<DSID>,\"targetDsId\":<DSID>,\"sourceDb\":\"sync_src\",\"targetDb\":\"sync_dst\",\"syncMode\":\"INCREMENTAL\",\"writeMode\":\"UPSERT\",\"batchSize\":1000,\"tableConfig\":\"[{\\\"sourceTable\\\":\\\"t_order\\\",\\\"targetTable\\\":\\\"t_order\\\",\\\"createTargetTable\\\":true,\\\"incrementalField\\\":\\\"updated_at\\\",\\\"incrementalType\\\":\\\"TIMESTAMP\\\"}]\"}'
```

记录任务 id `<JOBID>`。

- [ ] **Step 4: 首次运行（应自动建表 + 同步 2 行）**

```powershell
curl.exe -s -X POST "http://localhost:8099/api/sync-job/<JOBID>/run"
mysql -uroot -p"<DB_PASSWORD>" -e "SELECT COUNT(*) FROM sync_dst.t_order; SHOW CREATE TABLE sync_dst.t_order\G"
```
Expected: 目标表被自动创建，COUNT=2。

- [ ] **Step 5: 验证断点已回写**

```powershell
curl.exe -s "http://localhost:8099/api/sync-job/<JOBID>" 
```
Expected: 返回 JSON 中 `tableConfig` 的 `incrementalValue` 已更新为 `2026-01-02 10:00:00`。

- [ ] **Step 6: 源表追加增量数据后再次运行**

```powershell
mysql -uroot -p"<DB_PASSWORD>" -e "INSERT INTO sync_src.t_order VALUES (3,300,'2026-01-03 10:00:00','c');"
curl.exe -s -X POST "http://localhost:8099/api/sync-job/<JOBID>/run"
mysql -uroot -p"<DB_PASSWORD>" -e "SELECT COUNT(*) AS cnt FROM sync_dst.t_order;"
```
Expected: COUNT=3（只增量同步了新增的第 3 行，readCount=1）。查 `/executions` 确认最近一次 `readCount=1`。

- [ ] **Step 7: （可选 Doris）若有 Doris/StarRocks 环境**

配置一个 type=`Doris` 的数据源，建 INCREMENTAL/FULL 任务（targetDb 指向 Doris 库，createTargetTable=true），运行后确认 Doris 端表为 Unique Key 模型、数据正确。

- [ ] **Step 8: 记录验证结果并提交**

在本文件末尾追加"## 验证结果"小节，写明：自动建表是否成功、首次/增量 COUNT、断点回写值、Doris（如验证）结果。

```
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" add docs/superpowers/plans/2026-05-27-db-sync-plan-2-incremental-schema-doris.md
git -C "D:/data/datanote/.claude/worktrees/ecstatic-ramanujan-fc4ce3" commit -m "docs(sync): 计划2 端到端验证结果"
```

---

## 后续计划（概要）

- **计划 3（CDC 嵌入式）**：pom 加 debezium 1.9.7；`CdcSyncEngine`（消费 SourceRecord，复用 INSERT/UPDATE/DELETE 写入）；`JdbcOffsetBackingStore`+`JdbcSchemaHistory`（写 dn_cdc_offset/dn_cdc_schema_history）；`CdcEngineManager` 生命周期 + 重启恢复。首步先做 Java 8 兼容性最小验证。
- **计划 4（调度 + 前端 + 部署）**：`TaskSchedulerService` 加 `syncJob` 分支接入每日调度；前端 `view-dbsync`；SSH 服务器核实 + 部署联调 + CDC 前置条件文档。

---

## Self-Review 记录

- **Spec 覆盖**：覆盖设计文档第六节"增量引擎（策略模式 TIMESTAMP/AUTO_INCREMENT + 断点）、类型映射、自动建表（MySQL/Doris Unique Key）"。CDC/调度/前端在后续计划。
- **接口一致性**：`WriteSqlBuilder.build(writeMode,db,table,cols,pks)`、`MysqlConnector.buildIncrementalPageSql(db,table,cols,incField)`、`DbConnector.getColumnDefs(db,table)`、`TableSchemaService.buildDdl(targetType,db,table,cols)`/`ensureTargetTable(target,db,table,cols)`、`SyncEngine.sync(ctx)`、`IncrementalStrategy.compare/toStored/type`、`SyncEngineFactory.get(syncMode)`、`SyncJobService.updateTableConfig(jobId,tables)` 在各任务间一致；均衔接计划 1 真实签名。
- **占位符**：无 TODO/TBD；每个代码步骤含完整代码。
- **已知限制（沿用计划1）**：`run()` 同步阻塞、单列主键限制（全量）；增量要求源表有 `incrementalField`；自动建表要求源表有主键。无主键/复合主键全量回退（流式读）未纳入本计划，留作后续增强（如需要再单列任务）。
