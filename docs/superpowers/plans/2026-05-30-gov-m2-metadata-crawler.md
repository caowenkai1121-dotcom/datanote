# 数据治理 M2：元数据自动采集 Crawler 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 让 DataNote 自动从 MySQL 源库与 Doris 数仓采集 `information_schema`（库/表/字段/类型/注释/行数/体量），增量 upsert 进 `dn_table_meta`/`dn_column_meta`，记录采集日志，支持手动触发与每日定时；并把 `governance.html` 的「资产目录」从占位升级为真实可用（采集按钮 + 日志 + 资产表列表）。

**Architecture:** 新增 `MetadataCrawlerService`：源库经 `DriverManager`、数仓经 `HiveConfig` 连接池，统一 SQL 查 `information_schema`。技术字段（dbType/tableType/rowCount/sizeBytes/dataType 等）每次采集覆盖更新，业务字段（owner/tags/importance/table_comment/business_desc）做"空才填、非空保留"的合并，**绝不在 JVM 拉全量数据**。采集合并逻辑抽成静态纯函数单测；DB I/O 为集成路径。`@Scheduled` 每日 01:00 全量采集，复用 `@EnableScheduling`。前端「资产目录」用 `dn-common.js` 的 `DN.api` 调采集与列表接口。

**Tech Stack:** Java 8 / Spring Boot 2.7 / MyBatis-Plus；JUnit 5；vanilla JS。

**迁移耦合提示：** 实体新增字段后，运维须先应用 `sql/32_metadata_collect.sql` 再部署本里程碑代码（与本仓库编号迁移手动应用惯例一致）。

**范围说明：** 总体设计 §7 M2 提到的「补主题域管理界面」移出本里程碑（与采集正交），留作后续独立小项，避免 M2 膨胀。

**参照：** `docs/superpowers/specs/2026-05-30-data-governance-design.md` §6.1/§7 M2。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `sql/32_metadata_collect.sql` | dn_table_meta/dn_column_meta 扩列 + dn_meta_collect_log 建表（幂等） | Create |
| `src/main/java/com/datanote/model/DnTableMeta.java` | 新增 dbType/tableType/sizeBytes/lastCollectedAt 字段 | Modify |
| `src/main/java/com/datanote/model/DnColumnMeta.java` | 新增 dataType/columnKey/isNullable/ordinal/lastCollectedAt 字段 | Modify |
| `src/main/java/com/datanote/model/DnMetaCollectLog.java` | 采集日志实体 | Create |
| `src/main/java/com/datanote/mapper/DnMetaCollectLogMapper.java` | 采集日志 Mapper | Create |
| `src/main/java/com/datanote/service/MetadataCrawlerService.java` | 采集服务（纯合并函数 + 采集 + 定时） | Create |
| `src/test/java/com/datanote/service/MetadataCrawlerMergeTest.java` | 纯合并函数单测 | Create |
| `src/main/java/com/datanote/controller/MetadataCenterController.java` | 采集触发与日志查询端点 | Modify |
| `src/main/resources/static/governance.html` | 资产目录模块（采集按钮+日志+资产列表） | Modify |
| `src/test/java/com/datanote/web/GovernanceAssetsUiTest.java` | 资产目录前端断言 | Create |

---

## Task 1：DB 迁移 + 实体字段 + 日志模型/Mapper

**Files:**
- Create: `sql/32_metadata_collect.sql`
- Modify: `src/main/java/com/datanote/model/DnTableMeta.java`, `src/main/java/com/datanote/model/DnColumnMeta.java`
- Create: `src/main/java/com/datanote/model/DnMetaCollectLog.java`, `src/main/java/com/datanote/mapper/DnMetaCollectLogMapper.java`

- [ ] **Step 1：建迁移脚本**

创建 `sql/32_metadata_collect.sql`：

```sql
-- 数据治理 M2：元数据采集扩列 + 采集日志（幂等，按 information_schema 守护）
USE datanote;

-- dn_table_meta 扩列（仅 db_type 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_table_meta' AND COLUMN_NAME = 'db_type');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_table_meta
     ADD COLUMN db_type VARCHAR(20) DEFAULT NULL COMMENT ''来源类型 MYSQL/DORIS'',
     ADD COLUMN table_type VARCHAR(30) DEFAULT NULL COMMENT ''表类型 BASE TABLE/VIEW'',
     ADD COLUMN size_bytes BIGINT DEFAULT NULL COMMENT ''数据体量(字节)'',
     ADD COLUMN last_collected_at DATETIME DEFAULT NULL COMMENT ''最近采集时间''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- dn_column_meta 扩列（仅 data_type 不存在时整批添加）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dn_column_meta' AND COLUMN_NAME = 'data_type');
SET @s := IF(@c = 0,
  'ALTER TABLE dn_column_meta
     ADD COLUMN data_type VARCHAR(120) DEFAULT NULL COMMENT ''物理类型(如 varchar(50))'',
     ADD COLUMN column_key VARCHAR(16) DEFAULT NULL COMMENT ''键标识 PRI/MUL/UNI'',
     ADD COLUMN is_nullable VARCHAR(8) DEFAULT NULL COMMENT ''是否可空 YES/NO'',
     ADD COLUMN ordinal INT DEFAULT NULL COMMENT ''字段序号'',
     ADD COLUMN last_collected_at DATETIME DEFAULT NULL COMMENT ''最近采集时间''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 采集日志
CREATE TABLE IF NOT EXISTS dn_meta_collect_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  datasource_id BIGINT DEFAULT NULL COMMENT '数据源ID(0=Doris数仓)',
  db_type       VARCHAR(20) DEFAULT NULL COMMENT 'MYSQL/DORIS',
  scope         VARCHAR(200) DEFAULT NULL COMMENT '采集范围(all 或 库名)',
  table_count   INT DEFAULT 0,
  column_count  INT DEFAULT 0,
  status        VARCHAR(20) DEFAULT NULL COMMENT 'success/error',
  message       TEXT,
  duration_ms   BIGINT DEFAULT 0,
  started_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  finished_at   DATETIME DEFAULT NULL,
  INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据采集日志';
```

- [ ] **Step 2：DnTableMeta 加字段**

在 `DnTableMeta.java` 的 `private Long rowCount;` 之后新增：

```java
    private String dbType;
    private String tableType;
    private Long sizeBytes;
    private LocalDateTime lastCollectedAt;
```

- [ ] **Step 3：DnColumnMeta 加字段**

在 `DnColumnMeta.java` 的 `private String tags;` 之后新增：

```java
    private String dataType;
    private String columnKey;
    private String isNullable;
    private Integer ordinal;
    private LocalDateTime lastCollectedAt;
```

- [ ] **Step 4：采集日志实体**

创建 `src/main/java/com/datanote/model/DnMetaCollectLog.java`：

```java
package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据采集日志 — 对应 dn_meta_collect_log
 */
@Data
@TableName("dn_meta_collect_log")
public class DnMetaCollectLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private String dbType;
    private String scope;
    private Integer tableCount;
    private Integer columnCount;
    private String status;
    private String message;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
```

- [ ] **Step 5：Mapper**

创建 `src/main/java/com/datanote/mapper/DnMetaCollectLogMapper.java`：

```java
package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnMetaCollectLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnMetaCollectLogMapper extends BaseMapper<DnMetaCollectLog> {
}
```

- [ ] **Step 6：编译**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 7：提交**

```bash
git add sql/32_metadata_collect.sql src/main/java/com/datanote/model/DnTableMeta.java src/main/java/com/datanote/model/DnColumnMeta.java src/main/java/com/datanote/model/DnMetaCollectLog.java src/main/java/com/datanote/mapper/DnMetaCollectLogMapper.java
git commit -m "feat(gov-m2): 元数据采集扩列+采集日志表/实体/Mapper"
```

---

## Task 2：MetadataCrawlerService（纯合并函数 TDD + 采集 + 定时）

**Files:**
- Create: `src/main/java/com/datanote/service/MetadataCrawlerService.java`
- Test: `src/test/java/com/datanote/service/MetadataCrawlerMergeTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/service/MetadataCrawlerMergeTest.java`：

```java
package com.datanote.service;

import com.datanote.model.DnColumnMeta;
import com.datanote.model.DnTableMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataCrawlerMergeTest {

    @Test
    void mergeTableUpdatesTechnicalFields() {
        DnTableMeta t = new DnTableMeta();
        MetadataCrawlerService.mergeTableTechnical(t, "DORIS", "BASE TABLE", 1234L, 5678L, "订单表");
        assertEquals("DORIS", t.getDbType());
        assertEquals("BASE TABLE", t.getTableType());
        assertEquals(1234L, t.getRowCount());
        assertEquals(5678L, t.getSizeBytes());
        assertEquals("订单表", t.getTableComment());
    }

    @Test
    void mergeTablePreservesManualCommentAndNullRows() {
        DnTableMeta t = new DnTableMeta();
        t.setTableComment("人工业务描述");
        t.setRowCount(99L);
        MetadataCrawlerService.mergeTableTechnical(t, "MYSQL", "BASE TABLE", null, 10L, "DB注释");
        assertEquals("人工业务描述", t.getTableComment(), "已有业务描述不应被 DB 注释覆盖");
        assertEquals(99L, t.getRowCount(), "rows 为 null 时保留原值");
        assertEquals(10L, t.getSizeBytes());
    }

    @Test
    void mergeColumnUpdatesTechnicalAndFillsBlankDesc() {
        DnColumnMeta c = new DnColumnMeta();
        MetadataCrawlerService.mergeColumnTechnical(c, "varchar(50)", "PRI", "NO", 1, "用户名");
        assertEquals("varchar(50)", c.getDataType());
        assertEquals("PRI", c.getColumnKey());
        assertEquals("NO", c.getIsNullable());
        assertEquals(1, c.getOrdinal());
        assertEquals("用户名", c.getBusinessDesc());
    }

    @Test
    void mergeColumnPreservesManualDesc() {
        DnColumnMeta c = new DnColumnMeta();
        c.setBusinessDesc("人工填写");
        MetadataCrawlerService.mergeColumnTechnical(c, "int", "", "YES", 2, "DB注释");
        assertEquals("人工填写", c.getBusinessDesc());
        assertEquals("int", c.getDataType());
    }

    @Test
    void mergeTableBlankCommentFillsFromDb() {
        DnTableMeta t = new DnTableMeta();
        t.setTableComment("");
        MetadataCrawlerService.mergeTableTechnical(t, "MYSQL", "BASE TABLE", 1L, 1L, "来自DB");
        assertEquals("来自DB", t.getTableComment());
        assertNull(new DnTableMeta().getOwner());
    }
}
```

- [ ] **Step 2：运行，确认失败**

Run: `mvn -q -Dtest=MetadataCrawlerMergeTest test`
Expected: 编译失败 —— `MetadataCrawlerService` 不存在。

- [ ] **Step 3：实现采集服务**

创建 `src/main/java/com/datanote/service/MetadataCrawlerService.java`：

```java
package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.config.HiveConfig;
import com.datanote.mapper.DnColumnMetaMapper;
import com.datanote.mapper.DnDatasourceMapper;
import com.datanote.mapper.DnMetaCollectLogMapper;
import com.datanote.mapper.DnTableMetaMapper;
import com.datanote.model.DnColumnMeta;
import com.datanote.model.DnDatasource;
import com.datanote.model.DnMetaCollectLog;
import com.datanote.model.DnTableMeta;
import com.datanote.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 元数据自动采集服务 — 从 MySQL 源库 / Doris 数仓 读取 information_schema，增量 upsert 元数据。
 * 技术字段覆盖更新，业务字段"空才填、非空保留"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataCrawlerService {

    private final DnDatasourceMapper datasourceMapper;
    private final DnTableMetaMapper tableMetaMapper;
    private final DnColumnMetaMapper columnMetaMapper;
    private final DnMetaCollectLogMapper collectLogMapper;
    private final HiveConfig hiveConfig;

    @Value("${datanote.crypto.key}")
    private String cryptoKey;

    private static final String SQL_SCHEMATA =
            "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
            + "WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys') "
            + "ORDER BY SCHEMA_NAME";
    private static final String SQL_TABLES =
            "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_ROWS, DATA_LENGTH, TABLE_TYPE "
            + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    private static final String SQL_COLUMNS =
            "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, COLUMN_KEY, IS_NULLABLE, ORDINAL_POSITION "
            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";

    private static final long WAREHOUSE_DS_ID = 0L;

    // ========== 对外采集入口 ==========

    /** 采集全部：所有源数据源 + Doris 数仓 */
    public void crawlAll() {
        List<DnDatasource> all = datasourceMapper.selectList(null);
        for (DnDatasource ds : all) {
            try {
                crawlDatasource(ds.getId());
            } catch (Exception e) {
                log.error("采集数据源失败 dsId={}", ds.getId(), e);
            }
        }
        try {
            crawlWarehouse();
        } catch (Exception e) {
            log.error("采集 Doris 数仓失败", e);
        }
    }

    /** 采集指定 MySQL 源数据源（全部非系统库） */
    public DnMetaCollectLog crawlDatasource(Long datasourceId) {
        DnDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }
        String password = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        String url = "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort()
                + "/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
        return runCollect(datasourceId, "MYSQL", "all", () ->
                DriverManager.getConnection(url, ds.getUsername(), password));
    }

    /** 采集 Doris 数仓（datasourceId=0，连接池） */
    public DnMetaCollectLog crawlWarehouse() {
        return runCollect(WAREHOUSE_DS_ID, "DORIS", "all", hiveConfig::getConnection);
    }

    // ========== 采集核心 ==========

    @FunctionalInterface
    interface ConnSupplier { Connection get() throws SQLException; }

    private DnMetaCollectLog runCollect(Long datasourceId, String dbType, String scope, ConnSupplier supplier) {
        DnMetaCollectLog logRec = new DnMetaCollectLog();
        logRec.setDatasourceId(datasourceId);
        logRec.setDbType(dbType);
        logRec.setScope(scope);
        logRec.setStartedAt(LocalDateTime.now());
        long start = System.currentTimeMillis();
        int tableCount = 0;
        int columnCount = 0;
        try (Connection conn = supplier.get()) {
            List<String> dbs = listSchemas(conn);
            for (String db : dbs) {
                List<String[]> tables = listTables(conn, db);
                for (String[] t : tables) {
                    Long tableMetaId = upsertTable(datasourceId, dbType, db, t);
                    tableCount++;
                    columnCount += upsertColumns(conn, db, t[0], tableMetaId);
                }
            }
            logRec.setStatus("success");
        } catch (Exception e) {
            logRec.setStatus("error");
            logRec.setMessage(e.getMessage());
            log.error("元数据采集失败 dsId={} dbType={}", datasourceId, dbType, e);
        }
        logRec.setTableCount(tableCount);
        logRec.setColumnCount(columnCount);
        logRec.setDurationMs(System.currentTimeMillis() - start);
        logRec.setFinishedAt(LocalDateTime.now());
        collectLogMapper.insert(logRec);
        return logRec;
    }

    private List<String> listSchemas(Connection conn) throws SQLException {
        List<String> dbs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL_SCHEMATA)) {
            while (rs.next()) dbs.add(rs.getString(1));
        }
        return dbs;
    }

    /** 返回每张表的 [name, comment, rows, dataLength, tableType] */
    private List<String[]> listTables(Connection conn, String db) throws SQLException {
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_TABLES)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[]{
                            rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_COMMENT"),
                            rs.getObject("TABLE_ROWS") == null ? null : String.valueOf(rs.getLong("TABLE_ROWS")),
                            rs.getObject("DATA_LENGTH") == null ? null : String.valueOf(rs.getLong("DATA_LENGTH")),
                            rs.getString("TABLE_TYPE")
                    });
                }
            }
        }
        return tables;
    }

    private Long upsertTable(Long datasourceId, String dbType, String db, String[] t) {
        DnTableMeta meta = findTable(datasourceId, db, t[0]);
        boolean isNew = meta == null;
        if (isNew) {
            meta = new DnTableMeta();
            meta.setDatasourceId(datasourceId);
            meta.setDatabaseName(db);
            meta.setTableName(t[0]);
            meta.setCreatedAt(LocalDateTime.now());
        }
        mergeTableTechnical(meta, dbType, t[4], parseLong(t[2]), parseLong(t[3]), t[1]);
        meta.setLastCollectedAt(LocalDateTime.now());
        meta.setUpdatedAt(LocalDateTime.now());
        if (isNew) {
            tableMetaMapper.insert(meta);
        } else {
            tableMetaMapper.updateById(meta);
        }
        return meta.getId();
    }

    private int upsertColumns(Connection conn, String db, String table, Long tableMetaId) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(SQL_COLUMNS)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    DnColumnMeta col = findColumn(tableMetaId, colName);
                    boolean isNew = col == null;
                    if (isNew) {
                        col = new DnColumnMeta();
                        col.setTableMetaId(tableMetaId);
                        col.setColumnName(colName);
                        col.setCreatedAt(LocalDateTime.now());
                    }
                    Integer ord = rs.getObject("ORDINAL_POSITION") == null ? null : rs.getInt("ORDINAL_POSITION");
                    mergeColumnTechnical(col, rs.getString("COLUMN_TYPE"), rs.getString("COLUMN_KEY"),
                            rs.getString("IS_NULLABLE"), ord, rs.getString("COLUMN_COMMENT"));
                    col.setLastCollectedAt(LocalDateTime.now());
                    col.setUpdatedAt(LocalDateTime.now());
                    if (isNew) {
                        columnMetaMapper.insert(col);
                    } else {
                        columnMetaMapper.updateById(col);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private DnTableMeta findTable(Long datasourceId, String db, String table) {
        QueryWrapper<DnTableMeta> qw = new QueryWrapper<>();
        qw.eq("datasource_id", datasourceId).eq("database_name", db).eq("table_name", table).last("LIMIT 1");
        return tableMetaMapper.selectOne(qw);
    }

    private DnColumnMeta findColumn(Long tableMetaId, String columnName) {
        QueryWrapper<DnColumnMeta> qw = new QueryWrapper<>();
        qw.eq("table_meta_id", tableMetaId).eq("column_name", columnName).last("LIMIT 1");
        return columnMetaMapper.selectOne(qw);
    }

    // ========== 纯函数（可单测） ==========

    /** 合并表技术字段：技术字段覆盖，业务描述空才填 */
    static void mergeTableTechnical(DnTableMeta t, String dbType, String tableType,
                                    Long rows, Long dataLength, String dbComment) {
        t.setDbType(dbType);
        t.setTableType(tableType);
        if (rows != null) t.setRowCount(rows);
        t.setSizeBytes(dataLength);
        if (isBlank(t.getTableComment()) && !isBlank(dbComment)) {
            t.setTableComment(dbComment);
        }
    }

    /** 合并字段技术信息：技术字段覆盖，业务描述空才填 */
    static void mergeColumnTechnical(DnColumnMeta c, String dataType, String columnKey,
                                     String nullable, Integer ordinal, String dbComment) {
        c.setDataType(dataType);
        c.setColumnKey(columnKey);
        c.setIsNullable(nullable);
        c.setOrdinal(ordinal);
        if (isBlank(c.getBusinessDesc()) && !isBlank(dbComment)) {
            c.setBusinessDesc(dbComment);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    // ========== 定时采集 ==========

    /** 每日 01:00 全量采集 */
    @Scheduled(cron = "0 0 1 * * ?")
    public void scheduledCrawl() {
        log.info("启动每日元数据采集");
        crawlAll();
    }
}
```

- [ ] **Step 4：运行测试，确认通过**

Run: `mvn -q -Dtest=MetadataCrawlerMergeTest test`
Expected: PASS（5 用例）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/datanote/service/MetadataCrawlerService.java src/test/java/com/datanote/service/MetadataCrawlerMergeTest.java
git commit -m "feat(gov-m2): MetadataCrawlerService 采集源库/数仓元数据(纯合并函数+每日定时)"
```

---

## Task 3：采集触发与日志查询端点

**Files:**
- Modify: `src/main/java/com/datanote/controller/MetadataCenterController.java`

- [ ] **Step 1：注入采集服务与日志 Mapper + 新增端点**

在 `MetadataCenterController.java`：

(a) import 区新增：

```java
import com.datanote.mapper.DnMetaCollectLogMapper;
import com.datanote.model.DnMetaCollectLog;
import com.datanote.service.MetadataCrawlerService;
```

(b) 字段区（`private final DnColumnMetaMapper columnMetaMapper;` 之后）新增：

```java
    private final MetadataCrawlerService crawlerService;
    private final DnMetaCollectLogMapper collectLogMapper;
```

(c) 在 `stats()` 方法之后、类结束 `}` 之前新增端点：

```java
    /**
     * 手动触发采集：指定源数据源
     */
    @Operation(summary = "采集指定数据源元数据")
    @PostMapping("/crawl/datasource/{id}")
    public R<DnMetaCollectLog> crawlDatasource(@PathVariable Long id) {
        return R.ok(crawlerService.crawlDatasource(id));
    }

    /**
     * 手动触发采集：Doris 数仓
     */
    @Operation(summary = "采集Doris数仓元数据")
    @PostMapping("/crawl/warehouse")
    public R<DnMetaCollectLog> crawlWarehouse() {
        return R.ok(crawlerService.crawlWarehouse());
    }

    /**
     * 手动触发采集：全部（源库 + 数仓，异步执行避免请求超时）
     */
    @Operation(summary = "采集全部元数据")
    @PostMapping("/crawl/all")
    public R<String> crawlAll() {
        new Thread(crawlerService::crawlAll, "meta-crawl-all").start();
        return R.ok("采集已启动，完成后可在采集日志查看");
    }

    /**
     * 采集日志列表（最近 50 条）
     */
    @Operation(summary = "采集日志")
    @GetMapping("/collect-logs")
    public R<List<DnMetaCollectLog>> collectLogs() {
        QueryWrapper<DnMetaCollectLog> qw = new QueryWrapper<>();
        qw.orderByDesc("started_at").last("LIMIT 50");
        return R.ok(collectLogMapper.selectList(qw));
    }
```

- [ ] **Step 2：编译**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3：提交**

```bash
git add src/main/java/com/datanote/controller/MetadataCenterController.java
git commit -m "feat(gov-m2): 元数据采集触发(数据源/数仓/全部)与采集日志端点"
```

---

## Task 4：governance.html 资产目录模块

**Files:**
- Modify: `src/main/resources/static/governance.html`
- Test: `src/test/java/com/datanote/web/GovernanceAssetsUiTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/web/GovernanceAssetsUiTest.java`：

```java
package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceAssetsUiTest {

    private static String gov() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/static/governance.html")), StandardCharsets.UTF_8);
    }

    @Test
    void assetsModuleIsLiveAndCallsCrawlAndList() throws Exception {
        String html = gov();
        assertTrue(html.contains("renderAssets"), "资产目录应有专门渲染函数");
        assertTrue(html.contains("/api/metadata-center/crawl/all"), "应能触发全量采集");
        assertTrue(html.contains("/api/metadata-center/collect-logs"), "应能查询采集日志");
        assertTrue(html.contains("/api/metadata-center/tables"), "应能列出资产表元数据");
    }
}
```

- [ ] **Step 2：运行，确认失败**

Run: `mvn -q -Dtest=GovernanceAssetsUiTest test`
Expected: FAIL。

- [ ] **Step 3：实现资产目录模块**

在 `governance.html` 的 `GOV_MODULES` 数组中，把 `assets` 项的 `status: 'planned'` 改为 `status: 'live'`（保留其余字段）：

```javascript
  { key: 'assets',         label: '资产目录',   status: 'live',    ms: 'M2 / M10', desc: '元数据自动采集、数据目录、资产盘点与生命周期' },
```

在 `renderContent` 函数中，`if (m.status === 'live' && m.link)` 分支之前，新增按 key 分发的自定义渲染：

把：

```javascript
  if (m.status === 'live' && m.link) {
```

替换为：

```javascript
  if (m.status === 'live' && typeof MODULE_RENDERERS[m.key] === 'function') {
    MODULE_RENDERERS[m.key](c);
    return;
  }
  if (m.status === 'live' && m.link) {
```

在 `function route()` 之前新增模块渲染器与资产目录实现：

```javascript
var MODULE_RENDERERS = {
  assets: renderAssets
};

function fmtBytes(n) {
  if (n == null) return '-';
  var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0, v = Number(n);
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toFixed(1) + ' ' + u[i];
}

function renderAssets(c) {
  var bar = DN.h('div', { class: 'gov-desc' });
  var btnAll = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '采集全部(源库+数仓)',
    onclick: function () {
      DN.post('/api/metadata-center/crawl/all').then(function (msg) {
        DN.toast(msg || '采集已启动'); setTimeout(function () { loadAssets(c); }, 1500);
      }).catch(function (e) { DN.toast(e.message, 'error'); });
    } });
  bar.appendChild(btnAll);
  c.appendChild(bar);

  var logBox = DN.h('div', { id: 'assetLogs', class: 'gov-desc' });
  c.appendChild(logBox);

  var listBox = DN.h('div', { id: 'assetList' });
  c.appendChild(listBox);

  loadAssets(c);
}

function loadAssets() {
  DN.get('/api/metadata-center/collect-logs').then(function (logs) {
    var box = document.getElementById('assetLogs');
    if (!box) return;
    if (!logs || !logs.length) { box.textContent = '尚无采集记录'; return; }
    var last = logs[0];
    box.textContent = '最近采集: ' + (last.dbType || '') + ' / ' + (last.status || '') +
      ' / 表' + (last.tableCount || 0) + ' 字段' + (last.columnCount || 0) +
      ' / ' + (last.startedAt || '');
  }).catch(function () {});

  DN.get('/api/metadata-center/tables').then(function (tables) {
    var box = document.getElementById('assetList');
    if (!box) return;
    box.innerHTML = '';
    if (!tables || !tables.length) {
      box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '暂无资产，点击上方采集' }));
      return;
    }
    var rows = tables.slice(0, 200).map(function (t) {
      return '<tr><td>' + DN.esc(t.dbType || '') + '</td><td>' + DN.esc(t.databaseName || '') +
        '</td><td>' + DN.esc(t.tableName || '') + '</td><td>' + DN.esc(t.tableComment || '') +
        '</td><td style="text-align:right">' + (t.rowCount == null ? '-' : t.rowCount) +
        '</td><td style="text-align:right">' + fmtBytes(t.sizeBytes) + '</td></tr>';
    }).join('');
    box.innerHTML = '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px">' +
      '<thead><tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
      '<th style="padding:8px">来源</th><th style="padding:8px">库</th><th style="padding:8px">表</th>' +
      '<th style="padding:8px">业务描述</th><th style="padding:8px;text-align:right">行数</th>' +
      '<th style="padding:8px;text-align:right">体量</th></tr></thead><tbody>' + rows + '</tbody></table>';
    box.querySelectorAll('td').forEach(function (td) { td.style.padding = '8px'; td.style.borderBottom = '1px solid #f2f3f5'; });
  }).catch(function (e) {
    var box = document.getElementById('assetList');
    if (box) box.appendChild(DN.h('div', { class: 'gov-placeholder', text: '加载失败: ' + e.message }));
  });
}
```

> 说明：`/api/metadata-center/tables` 现有端点支持 `keyword`/`datasourceId`/`tag` 可选参数，无参时返回最近更新的前 100 条，正好用于资产列表。

- [ ] **Step 4：运行测试，确认通过 + 回归前端**

Run: `mvn -q -Dtest=GovernanceAssetsUiTest,GovernanceShellTest,QualityDorisUiTest test`
Expected: PASS。

- [ ] **Step 5：提交**

```bash
git add src/main/resources/static/governance.html src/test/java/com/datanote/web/GovernanceAssetsUiTest.java
git commit -m "feat(gov-m2): 治理中心资产目录上线(采集触发+采集日志+资产表列表)"
```

---

## Task 5：M2 全量回归 + 手动验证

- [ ] **Step 1：全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，无回归。

- [ ] **Step 2：手动验证（需先应用 sql/32 迁移 + 活 MySQL/Doris）**

- 运维执行 `mysql ... datanote < sql/32_metadata_collect.sql`。
- governance.html → 资产目录 → 点「采集全部」→ 采集日志出现成功记录，资产表列表显示库/表/行数/体量。
- `dn_table_meta` 的 `row_count`/`size_bytes`/`db_type`/`last_collected_at` 被填充；人工编辑过的 `owner`/`tags`/`table_comment` 不被覆盖。

---

## Self-Review 记录

- **Spec 覆盖**：M2「定时/手动采集 MySQL/Doris information_schema，增量 upsert，扩列复用，挂调度」→ Task1（扩列+日志）+ Task2（采集服务+@Scheduled）+ Task3（手动触发端点）+ Task4（资产目录 UI）。主题域管理界面已显式移出并记录。
- **占位符**：无。
- **类型一致**：`mergeTableTechnical(DnTableMeta,String,String,Long,Long,String)`、`mergeColumnTechnical(DnColumnMeta,String,String,String,Integer,String)` 签名与测试一致；新字段 `dbType/tableType/sizeBytes/lastCollectedAt`（表）、`dataType/columnKey/isNullable/ordinal/lastCollectedAt`（列）在实体、SQL、采集服务、前端列表间一致；端点路径 `/api/metadata-center/crawl/all`、`/collect-logs`、`/tables` 与前端/测试一致。
