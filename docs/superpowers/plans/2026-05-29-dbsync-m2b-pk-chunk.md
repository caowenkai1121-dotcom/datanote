# M2b 多列/无主键回退 + 全量 chunk 续传 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 放宽三引擎"仅单列主键"限制(支持多列主键复合游标、无主键流式回退),并给全量同步加 chunk 断点续传(中断重跑从上次游标继续)。

**Architecture:** `MysqlConnector` 加复合主键 keyset SQL(行值比较 `(pk1,pk2)>(?,?)`)与无主键流式 SQL。`FieldMappingResolver.Resolved` 暴露 `pkSourceColumns`/`pkTargetColumns`(有序列表)。`FullSyncEngine` 按主键数分派:1→单列(现状)、>1→复合 keyset、0→流式单遍+强制 INSERT;并在每页提交后把游标存 `dn_sync_chunk_checkpoint`,表开始时载入续传,表完成清除。`IncrementalSyncEngine` 支持多列主键复合游标(inc, pk1..pkn);无主键增量明确报错(留后续)。**只动全量/增量引擎与连接器,不碰 CDC/调度。**

**Tech Stack:** Java8、JUnit5、FastJSON2(游标 JSON 序列化)、MyBatis-Plus。零新依赖。

**约束/已知限制:** 复合游标用 MySQL 行值比较,源库须 MySQL 协议族(本版前提已满足);chunk 游标以字符串 JSON 持久化,适配数值/字符串/日期主键(二进制主键不支持,报错跳过续传)。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sql/27_sync_chunk_checkpoint.sql` | dn_sync_chunk_checkpoint 表 | Create |
| `model/DnSyncChunkCheckpoint.java` | 实体 | Create |
| `mapper/DnSyncChunkCheckpointMapper.java` | Mapper(BaseMapper) | Create |
| `sync/connector/MysqlConnector.java` | 复合 keyset + 无主键流式 SQL | Modify |
| `sync/util/FieldMappingResolver.java` | Resolved 暴露 pkSource/pkTarget 列表 | Modify |
| `sync/dto/SyncContext.java` | chunk 续传回调 | Modify |
| `sync/engine/FullSyncEngine.java` | 多/无主键分派 + chunk 续传 | Modify |
| `sync/engine/IncrementalSyncEngine.java` | 多列主键复合游标 | Modify |
| `sync/service/SyncJobService.java` | chunk 加载/保存/清除(JSON) | Modify |
| `sync/service/SyncJobExecutor.java` | 注入 chunk 回调 | Modify |

**统一契约:**
- `MysqlConnector.buildKeysetPageSqlMulti(db, table, columns, pkColumns(List), hasCursor, extraWhere)`:`SELECT cols FROM t [WHERE (pk1,..)>(?,..) [AND ew]] ORDER BY pk1 ASC,.. LIMIT ?`;无游标且有过滤则 `WHERE ew`。
- `MysqlConnector.buildFullScanSql(db, table, columns, extraWhere)`:`SELECT cols FROM t [WHERE ew]`(无 ORDER/LIMIT,流式)。
- `Resolved.pkSourceColumns`/`pkTargetColumns`:有序主键列(源名/目标名),与 pkColumns 顺序一致;无主键时空 list。保留旧 `pkTarget`。
- `SyncContext`:`chunkLoad`(Function<String,String> 源表→游标JSON或null)、`chunkSave`(BiConsumer<String,String> 源表,游标JSON)、`chunkClear`(Consumer<String>)。默认空操作。

---

## Task 1: chunk_checkpoint 表 + 实体 + Mapper

**Files:** Create `sql/27_sync_chunk_checkpoint.sql`、`model/DnSyncChunkCheckpoint.java`、`mapper/DnSyncChunkCheckpointMapper.java`

- [ ] **Step 1: DDL**(MySQL8,IF NOT EXISTS 对 CREATE TABLE 合法,故直接 CREATE TABLE IF NOT EXISTS)

```sql
USE datanote;
CREATE TABLE IF NOT EXISTS dn_sync_chunk_checkpoint (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    sync_job_id   BIGINT       NOT NULL,
    source_table  VARCHAR(128) NOT NULL,
    cursor_value  LONGTEXT     COMMENT 'JSON 数组:复合主键游标值(字符串)',
    row_count     BIGINT       DEFAULT 0,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_table (sync_job_id, source_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全量分片断点续传';
```

- [ ] **Step 2: 实体**(参考 model/DnCdcOffset.java 风格)

```java
package com.datanote.model;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("dn_sync_chunk_checkpoint")
public class DnSyncChunkCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long syncJobId;
    private String sourceTable;
    private String cursorValue;
    private Long rowCount;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Mapper**(参考 mapper/DnCdcOffsetMapper.java)

```java
package com.datanote.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnSyncChunkCheckpoint;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface DnSyncChunkCheckpointMapper extends BaseMapper<DnSyncChunkCheckpoint> {
}
```

- [ ] **Step 4:** `mvn -q compile` 通过。
- [ ] **Step 5: Commit** `feat(sync-m2b): chunk_checkpoint 表+实体+Mapper`

---

## Task 2: MysqlConnector 复合/无主键 SQL(TDD)

**Files:** Modify `sync/connector/MysqlConnector.java`；Test `MysqlConnectorMultiPkTest.java`

- [ ] **Step 1: 失败测试**(JUnit5)

```java
package com.datanote.sync.connector;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class MysqlConnectorMultiPkTest {
    @Test void multiNoCursor() {
        assertEquals("SELECT `a`, `b` FROM `db`.`t` ORDER BY `a` ASC, `b` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSqlMulti("db","t", Arrays.asList("a","b"), Arrays.asList("a","b"), false, ""));
    }
    @Test void multiWithCursor() {
        assertEquals("SELECT `a`, `b` FROM `db`.`t` WHERE (`a`, `b`) > (?, ?) ORDER BY `a` ASC, `b` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSqlMulti("db","t", Arrays.asList("a","b"), Arrays.asList("a","b"), true, ""));
    }
    @Test void multiCursorAndFilter() {
        assertEquals("SELECT `a` FROM `db`.`t` WHERE (`a`) > (?) AND (`x`=1) ORDER BY `a` ASC LIMIT ?",
            MysqlConnector.buildKeysetPageSqlMulti("db","t", Arrays.asList("a"), Arrays.asList("a"), true, "(`x`=1)"));
    }
    @Test void fullScanNoFilter() {
        assertEquals("SELECT `a`, `b` FROM `db`.`t`",
            MysqlConnector.buildFullScanSql("db","t", Arrays.asList("a","b"), ""));
    }
    @Test void fullScanFilter() {
        assertEquals("SELECT `a` FROM `db`.`t` WHERE (`x`=1)",
            MysqlConnector.buildFullScanSql("db","t", Arrays.asList("a"), "(`x`=1)"));
    }
}
```

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**(加两静态方法,沿用 SqlIdentifiers.quote 与现有风格)

```java
    /** 复合主键 keyset 分页。pkColumns 顺序即 ORDER BY 顺序;游标用行值比较 (pk..)>(?..)。 */
    public static String buildKeysetPageSqlMulti(String db, String table, java.util.List<String> columns,
                                                 java.util.List<String> pkColumns, boolean hasCursor, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String pkList = pkColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String orderBy = pkColumns.stream().map(p -> SqlIdentifiers.quote(p) + " ASC").collect(Collectors.joining(", "));
        boolean hasFilter = extraWhere != null && !extraWhere.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fullTable);
        String ph = pkColumns.stream().map(p -> "?").collect(Collectors.joining(", "));
        if (hasCursor && hasFilter) {
            sql.append(" WHERE (").append(pkList).append(") > (").append(ph).append(") AND ").append(extraWhere);
        } else if (hasCursor) {
            sql.append(" WHERE (").append(pkList).append(") > (").append(ph).append(")");
        } else if (hasFilter) {
            sql.append(" WHERE ").append(extraWhere);
        }
        sql.append(" ORDER BY ").append(orderBy).append(" LIMIT ?");
        return sql.toString();
    }

    /** 无主键全表流式扫描 SQL(无 ORDER/LIMIT,配合 setFetchSize(Integer.MIN_VALUE) 流式读)。 */
    public static String buildFullScanSql(String db, String table, java.util.List<String> columns, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fullTable);
        if (extraWhere != null && !extraWhere.trim().isEmpty()) sql.append(" WHERE ").append(extraWhere);
        return sql.toString();
    }
```

- [ ] **Step 4: 确认通过** `mvn -q -Dtest=MysqlConnectorMultiPkTest,MysqlConnectorPageSqlTest,MysqlConnectorTest test`
- [ ] **Step 5: Commit** `feat(sync-m2b): MysqlConnector 复合主键keyset+无主键流式SQL`

---

## Task 3: Resolved 暴露主键列表(TDD)

**Files:** Modify `sync/util/FieldMappingResolver.java`；Test `FieldMappingResolverPkListTest.java`

> 现 `resolve(tc, allColumns, pkSource)` 仅单主键。新增重载 `resolve(tc, allColumns, List<String> pkSources)`,Resolved 加 `pkSourceColumns`/`pkTargetColumns`(有序)。**保留旧单主键重载**委托新重载(单元素 list),保持 `pkTarget`=pkTargetColumns 首元素(无主键时 null)。

- [ ] **Step 1: 失败测试**

```java
package com.datanote.sync.util;
import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class FieldMappingResolverPkListTest {
    @Test void multiPkNoMapping() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("a","b","c"), Arrays.asList("a","b"));
        assertEquals(Arrays.asList("a","b"), r.pkSourceColumns);
        assertEquals(Arrays.asList("a","b"), r.pkTargetColumns);
    }
    @Test void noPkEmptyLists() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("a","b"), java.util.Collections.emptyList());
        assertTrue(r.pkSourceColumns.isEmpty());
        assertTrue(r.pkTargetColumns.isEmpty());
        assertNull(r.pkTarget);
    }
    @Test void multiPkWithMappingRenamesTarget() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMapping a=new FieldMapping(); a.setSource("a"); a.setTarget("A"); a.setSync(true);
        FieldMapping b=new FieldMapping(); b.setSource("b"); b.setTarget("B"); b.setSync(true);
        tc.setFields(Arrays.asList(a,b));
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("a","b"), Arrays.asList("a","b"));
        assertEquals(Arrays.asList("a","b"), r.pkSourceColumns);
        assertEquals(Arrays.asList("A","B"), r.pkTargetColumns);
    }
    @Test void singlePkBackCompat() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r = FieldMappingResolver.resolve(tc, Arrays.asList("id","n"), "id");
        assertEquals("id", r.pkTarget);
        assertEquals(Arrays.asList("id"), r.pkSourceColumns);
    }
}
```

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**
  - `Resolved` 加 `public final List<String> pkSourceColumns; public final List<String> pkTargetColumns;`。扩构造器(在 4 参基础上加 2 个 list 参);保留 4 参与 3 参构造器委托(pkSource/pkTarget 列表用 pkTarget 推断:pkTarget!=null→单元素,null→空)。
  - 新增 `resolve(tc, allColumns, List<String> pkSources)`:校验每个 pkSource 在选中列(映射场景);pkTargetColumns = 每个 pkSource 经 srcToTgt 映射(无映射=同名);pkSourceColumns=pkSources。无主键(pkSources 空)→两列表空、pkTarget=null,且**跳过**"主键必须在选中列"校验。
  - 旧 `resolve(tc, allColumns, String pkSource)` 改为:pkSource==null→委托空 list;否则委托 `Collections.singletonList(pkSource)`。
  - 旧 4 参 Resolved 构造器(srcColumns,tgtColumns,pkTarget,srcToFieldMapping)保留,内部 pkSourceColumns/pkTargetColumns 由 pkTarget 推断(兼容 M1 测试)。

- [ ] **Step 4: 确认通过** `mvn -q -Dtest=FieldMappingResolverPkListTest,FieldMappingResolverSrcMapTest,FieldMappingResolverTest test`
- [ ] **Step 5: Commit** `feat(sync-m2b): Resolved 暴露多主键源/目标列表`

---

## Task 4: FullSyncEngine 多/无主键 + chunk 续传

**Files:** Modify `sync/engine/FullSyncEngine.java`、`sync/dto/SyncContext.java`

> **先读** 当前 FullSyncEngine(M1/M2a 已改:RowValueProcessor + BatchWriter + 过滤 + 前后置SQL)。本任务把"仅单列主键"分派为三路,并接 chunk 续传回调。**复用** BatchWriter/RowValueProcessor/过滤/前后置SQL 不变。

- [ ] **Step 1: SyncContext 加 chunk 回调**

```java
    /** M2b：全量 chunk 续传回调。chunkLoad 源表→游标JSON(无则 null);chunkSave 存;chunkClear 清。 */
    private java.util.function.Function<String,String> chunkLoad = t -> null;
    private java.util.function.BiConsumer<String,String> chunkSave = (t,v) -> {};
    private java.util.function.Consumer<String> chunkClear = t -> {};
```

- [ ] **Step 2: FullSyncEngine.syncOneTable 改造**

主键数分派(取 `meta.getPrimaryKeys()`):
- 删除原 `if (meta.getPrimaryKeys().size() != 1) throw ...`。
- `List<String> pks = meta.getPrimaryKeys();`
- `FieldMappingResolver.Resolved fm = FieldMappingResolver.resolve(tc, meta.getColumns(), pks);`(新多主键重载)
- `int pkCount = pks.size();`
- **pkCount==0(无主键)**:走 `buildFullScanSql` 流式单遍读(`readPs.setFetchSize(Integer.MIN_VALUE)`),写模式强制 INSERT(若 ctx.writeMode 为 UPSERT 则本表用 INSERT 并 log 警告"无主键,降级 INSERT 不去重"),无 chunk 续传(log 提示)。一次 ResultSet 遍历完→bw.flush 分批(每攒 batchSize 行 flush 一次,用计数控制)。
- **pkCount>=1**:keyset 分页。游标用 `Object[] cursor`(长度 pkCount)。SQL 用 `buildKeysetPageSqlMulti(srcDb, sourceTable, srcColumns, fm.pkSourceColumns, hasCursor, extraWhere)`。绑定:hasCursor 时按 pkCount 个游标值 setObject,再 setInt(limit);首页只 setInt(limit)。游标推进:每读一行,把该行各 pk 源列值存入 cursorNext[](按 pkSourceColumns 在 srcColumns 中的索引取);页末用末行 pk 值更新 cursor。
  - **chunk 续传**:表开始 `String cj = ctx.getChunkLoad().apply(sourceTable); if(cj!=null){ cursor=parsePkJson(cj); hasCursor=true; }`;每页 bw.flush 成功后 `ctx.getChunkSave().accept(sourceTable, toPkJson(cursor))`;整表读完(末页 break 后)`ctx.getChunkClear().accept(sourceTable)`。游标 JSON:`com.alibaba.fastjson2.JSON.toJSONString(java.util.Arrays.stream(cursor).map(o->o==null?null:String.valueOf(o)).toArray())`;parse:`JSON.parseArray(cj).toArray()`(得到 String[],setObject 绑字符串,MySQL 隐式转型)。
- WriteSqlBuilder.build 传 `fm.pkTargetColumns`(多主键 UPSERT;无主键传空 list→INSERT 分支)。
- markTs/RowValueProcessor/BatchWriter/限速/前后置SQL 逻辑保持。

> 复杂度提示:把 keyset 与流式两路拆成两个 private 方法 `syncByKeyset(...)`/`syncByStreaming(...)`,syncOneTable 按 pkCount 选择,保持每个方法聚焦。chunk 续传仅在 keyset 路。

- [ ] **Step 3:** `mvn -q -Dtest=*Sync*,*Engine*,*Mysql* test` + `mvn -q compile`。
- [ ] **Step 4: Commit** `feat(sync-m2b): FullSyncEngine 多/无主键分派+chunk续传`

---

## Task 5: IncrementalSyncEngine 多列主键复合游标

**Files:** Modify `sync/engine/IncrementalSyncEngine.java`、`MysqlConnector.java`

> 现增量用 (inc, 单pk) 复合游标。扩展为 (inc, pk1..pkn)。无主键增量:明确报错(留后续轮),保留现有 incField 必填校验。

- [ ] **Step 1: MysqlConnector 加增量多主键 SQL(TDD)** `buildIncrementalPageSqlMulti(db,table,columns,incField,pkColumns(List),firstPage,extraWhere)`:首页 `inc >= ?`;后续 `(inc > ? OR (inc = ? AND (pk1,..)>(?,..)))`;尾接 extraWhere;`ORDER BY inc ASC, pk1 ASC,.. LIMIT ?`。补 2-3 个单测(2 列主键的首/后页 SQL)。

- [ ] **Step 2: IncrementalSyncEngine.syncOneTable**:`pks=meta.getPrimaryKeys(); if(pks.isEmpty()) throw new IllegalStateException("增量同步需至少一个主键列,表:"+sourceTable);` 删除 `size()!=1` 限制。游标 cursorPk 改为 `Object[]`(长度 pks.size());firstSql/nextSql 用 `buildIncrementalPageSqlMulti`;后续页绑定 `inc>? , inc=? , pk1.., limit`;maxValue/断点逻辑不变(断点仍只存 inc 值)。复用 BatchWriter/RowValueProcessor/限速。

- [ ] **Step 3:** `mvn -q -Dtest=*Incremental*,*Sync*,*Mysql* test` + `mvn -q compile`。
- [ ] **Step 4: Commit** `feat(sync-m2b): IncrementalSyncEngine 多列主键复合游标`

---

## Task 6: SyncJobService/Executor 接 chunk 持久化

**Files:** Modify `sync/service/SyncJobService.java`、`SyncJobExecutor.java`

- [ ] **Step 1: SyncJobService 加 chunk 方法**(注入 DnSyncChunkCheckpointMapper)
  - `String loadChunkCursor(Long jobId, String sourceTable)`:查 uk_job_table,返回 cursor_value 或 null。
  - `void saveChunkCursor(Long jobId, String sourceTable, String cursorJson)`:存在则 update cursor_value,否则 insert。
  - `void clearChunkCursor(Long jobId, String sourceTable)`:delete by (jobId, sourceTable)。
  (用 LambdaQueryWrapper,参考现有 mapper 用法)

- [ ] **Step 2: SyncJobExecutor.doRun 注入回调**(setCheckpointCallback 附近)

```java
        ctx.setChunkLoad(t -> syncJobService.loadChunkCursor(jobId, t));
        ctx.setChunkSave((t, v) -> syncJobService.saveChunkCursor(jobId, t, v));
        ctx.setChunkClear(t -> syncJobService.clearChunkCursor(jobId, t));
```

- [ ] **Step 3:** `mvn -q test` 全量不回归。
- [ ] **Step 4: Commit** `feat(sync-m2b): chunk 游标加载/保存/清除接入执行层`

---

## Self-Review

- **Spec 覆盖**: 多列主键(Task2/3/4/5)、无主键回退(Task2/4)、chunk 续传(Task1/4/6)。✅
- **类型一致**: `buildKeysetPageSqlMulti(...,List,List,boolean,String)`、`buildFullScanSql(...,List,String)`、`buildIncrementalPageSqlMulti(...)`、`Resolved.pkSourceColumns/pkTargetColumns`、`SyncContext.chunkLoad/chunkSave/chunkClear` 全程一致。✅
- **回归保护**: 单列主键走 pkCount>=1 keyset 路,与现状等价(复合 SQL 在单列退化为 `(pk)>(?)`,行为同 `pk>?`);chunk 回调默认空操作;旧 resolve 重载保留。✅
- **风险**: 无主键流式无续传/不去重(强制 INSERT)已 log 警告;复合游标依赖 MySQL 行值比较(源 MySQL 族,满足)。

---

## 部署(M2b 里程碑)

1. `mvn -o clean package` 全过。
2. paramiko 分块上传 jar(复用 deploy 脚本,改 LOCAL_SQL=27 与动态 md5)。
3. 跑 `sql/27_sync_chunk_checkpoint.sql`。
4. 备份+换jar+restart+验证(is-active/curl 8099→302/Started/CDC续传/job状态/dn_sync_chunk_checkpoint 表存在)。auto-rollback。
