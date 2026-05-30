# M4d SqlDialect 方言抽象层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 把散落的方言差异(写SQL UPSERT/INSERT、建表DDL、类型映射)收进 `SqlDialect` 接口与 Mysql/Doris/StarRocks 实现,新增方言只实现一个接口。**纯结构重构,行为零变化——现有 WriteSqlBuilderTest/TableSchemaServiceTest/TypeMappingServiceTest 是 byte-identical 护栏,必须全过不改断言。**

**Architecture:** 新增 `sync.dialect` 包:`SqlDialect` 接口(writeSql/createTableDdl/mapColumnType/name) + `MysqlDialect`(写SQL=ON DUPLICATE KEY/INSERT_IGNORE,DDL=PRIMARY KEY,类型=照搬) + `DorisDialect`(写SQL=INSERT,DDL=UNIQUE KEY+DISTRIBUTED,类型=mysqlToDoris) + `StarRocksDialect`(同 Doris) + `DialectFactory.of(type)`。`WriteSqlBuilder.build` 与 `TableSchemaService.buildDdl` 改为**委托** DialectFactory(保留方法签名,内部 body 搬入对应 dialect)。读 SQL(keyset/count/scan/delete)MySQL 协议族统一,不动。

**Tech Stack:** Java8、JUnit5。零新依赖。**无 DDL、无实体、无前端改动。**

---

## File Structure

| 文件 | 动作 |
|------|------|
| `sync/dialect/SqlDialect.java` | 接口 — Create |
| `sync/dialect/MysqlDialect.java` | MySQL 实现 — Create |
| `sync/dialect/DorisDialect.java` | Doris 实现 — Create |
| `sync/dialect/StarRocksDialect.java` | StarRocks(继承/复用 Doris) — Create |
| `sync/dialect/DialectFactory.java` | 按 type 选方言 — Create |
| `sync/util/WriteSqlBuilder.java` | build 委托 DialectFactory — Modify |
| `sync/schema/TableSchemaService.java` | buildDdl 委托 DialectFactory — Modify |

**契约(产出 SQL 必须与现状逐字节一致):**
- `SqlDialect.name()`:MYSQL/DORIS/STARROCKS。
- `writeSql(writeMode, db, table, columns, pkColumns)`:= 现 WriteSqlBuilder.build(name, ...) 的对应分支输出。
- `createTableDdl(db, table, columns)`:= 现 TableSchemaService.buildDdl(name, ...) 的对应分支输出。
- `mapColumnType(mysqlColumnType)`:MySQL=原样返回;Doris/StarRocks=TypeMappingService.mysqlToDoris。

---

## Task 1: SqlDialect 接口 + 三方言 + Factory(TDD)

**Files:** Create `sync/dialect/*.java` + `DialectFactoryTest.java`

> **先读** WriteSqlBuilder.build(两 build 重载,MySQL/Doris 分支)、TableSchemaService.buildDdl(MySQL/Doris 分支 + comment/escapeSqlLiteral 私有方法)、TypeMappingService.mysqlToDoris。把这些分支逻辑**原样**搬进对应 dialect(逻辑不改,产出不变)。

- [ ] **Step 1: 失败测试**(对照现有 WriteSqlBuilder/TableSchemaService 期望输出,确保 dialect 产出一致)

```java
package com.datanote.sync.dialect;
import com.datanote.sync.connector.ColumnDef;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class DialectFactoryTest {
    @Test void factorySelectsDialect() {
        assertEquals("MYSQL", DialectFactory.of("MYSQL").name());
        assertEquals("DORIS", DialectFactory.of("DORIS").name());
        assertEquals("STARROCKS", DialectFactory.of("STARROCKS").name());
        assertEquals("MYSQL", DialectFactory.of(null).name()); // 缺省 MySQL
    }
    @Test void mysqlWriteUpsert() {
        String s = DialectFactory.of("MYSQL").writeSql("UPSERT","db","t", Arrays.asList("id","name"), Arrays.asList("id"));
        assertEquals("INSERT INTO `db`.`t` (`id`, `name`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)", s);
    }
    @Test void dorisWritePlainInsert() {
        String s = DialectFactory.of("DORIS").writeSql("UPSERT","db","t", Arrays.asList("id","name"), Arrays.asList("id"));
        assertEquals("INSERT INTO `db`.`t` (`id`, `name`) VALUES (?, ?)", s);
    }
    @Test void mysqlTypeIdentity() { assertEquals("varchar(50)", DialectFactory.of("MYSQL").mapColumnType("varchar(50)")); }
    @Test void dorisTypeMapped() { assertEquals("STRING", DialectFactory.of("DORIS").mapColumnType("text")); }
}
```
> 注:上面写SQL期望串需与现有 WriteSqlBuilderTest 的断言一致,**先读 WriteSqlBuilderTest 确认确切格式**(逗号/空格),照抄。

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**
  - `SqlDialect` 接口:`String name(); String writeSql(String writeMode,String db,String table,List<String> columns,List<String> pkColumns); String createTableDdl(String db,String table,List<ColumnDef> columns); String mapColumnType(String mysqlColumnType);`
  - `MysqlDialect`:name="MYSQL";writeSql=搬 WriteSqlBuilder 的 MySQL 分支(INSERT/INSERT_IGNORE/UPSERT ON DUPLICATE KEY,空 set 退化 INSERT_IGNORE);createTableDdl=搬 TableSchemaService MySQL 分支(含 comment 转义);mapColumnType=原样返回入参。
  - `DorisDialect`:name="DORIS";writeSql=plain INSERT;createTableDdl=Doris UNIQUE KEY 分支(主键排前+DISTRIBUTED BUCKETS 10+replication_num);mapColumnType=委托 `new TypeMappingService().mysqlToDoris(...)`(或注入)。comment/escape 逻辑在 dialect 内复用(可抽 static util 或各自留一份小私有方法)。
  - `StarRocksDialect extends DorisDialect`:仅 name="STARROCKS"(写SQL/DDL/类型同 Doris)。
  - `DialectFactory.of(String type)`:DORIS→DorisDialect,STARROCKS→StarRocksDialect,其余(含 null)→MysqlDialect。可缓存单例。
  > comment 转义(escapeSqlLiteral)与 Doris 类型映射依赖:为零行为变化,把 TableSchemaService 的 comment/escapeSqlLiteral 私有逻辑原样复制进 dialect(或抽 `sync/dialect/DdlSupport.java` 静态助手供复用)。TypeMappingService 保留不动,DorisDialect 调它。

- [ ] **Step 4: 确认通过** `mvn -q -Dtest=DialectFactoryTest test`
- [ ] **Step 5: Commit** `feat(sync-m4d): SqlDialect 接口+Mysql/Doris/StarRocks方言+Factory`

---

## Task 2: WriteSqlBuilder/TableSchemaService 委托方言

**Files:** Modify `sync/util/WriteSqlBuilder.java`、`sync/schema/TableSchemaService.java`

> **关键护栏**:改完后 `WriteSqlBuilderTest`(6)、`TableSchemaServiceTest`(4)、`TypeMappingServiceTest`(5) 全过且**不改任何断言**——证明产出 byte-identical。

- [ ] **Step 1: WriteSqlBuilder.build 委托**

`build(targetType, writeMode, db, table, columns, pkColumns)` body 改为:
```java
        return com.datanote.sync.dialect.DialectFactory.of(targetType).writeSql(writeMode, db, table, columns, pkColumns);
```
2 参版(无 targetType,默认 MYSQL)的 `build(writeMode,db,table,columns,pkColumns)` 仍委托 `build("MYSQL", ...)`。**保留两个签名**(引擎/CDC 调用点不动)。原 MySQL/Doris 分支逻辑已搬入 dialect,此处删除原 body 实现。

- [ ] **Step 2: TableSchemaService.buildDdl 委托**

`buildDdl(targetType, db, table, columns)` body 改为:
```java
        return com.datanote.sync.dialect.DialectFactory.of(targetType).createTableDdl(db, table, columns);
```
原无主键检查移到 dialect.createTableDdl 内(MysqlDialect/DorisDialect 各自首部 `if (pks.isEmpty()) throw ...`,保持原异常信息)。`ensureTargetTable` 不变(仍调 buildDdl)。comment/escapeSqlLiteral 若仅 buildDdl 用,可随逻辑搬走或保留(保留无害)。typeMappingService 字段若不再被 TableSchemaService 直接用,可保留(DorisDialect 自持)。

- [ ] **Step 3: 全量回归(护栏)** `mvn -q test`——**全部通过,WriteSqlBuilderTest/TableSchemaServiceTest/TypeMappingServiceTest 断言不改**。若任一失败,说明 dialect 产出与原不一致,修 dialect 至 byte-identical。
- [ ] **Step 4: Commit** `feat(sync-m4d): WriteSqlBuilder/TableSchemaService 委托SqlDialect`

---

## Self-Review

- **Spec 覆盖**: SqlDialect 方言抽象层(B35)。✅ 新增方言 = 实现 SqlDialect 一个接口。
- **零行为变化(关键)**: 三 dialect 产出与原 WriteSqlBuilder/TableSchemaService/TypeMappingService 逐字节一致,由现有 15 个断言(Write6+Table4+Type5)护栏验证,**不改断言**。
- **类型一致**: SqlDialect.writeSql/createTableDdl/mapColumnType/name + DialectFactory.of 全程一致;WriteSqlBuilder/TableSchemaService 公共签名不变(引擎/CDC 调用点零改动)。✅
- **范围**: 读 SQL(keyset/count/scan/delete)MySQL 协议族统一,不纳入方言(无变化);CDC delete/logical-delete SQL 暂留 CdcSyncEngine(MySQL 协议族统一,后续如接异构再收)。

---

## 部署(M4d)

无 DDL。`mvn -o clean package`(263 测试全过,含 15 护栏断言不改)→ 分块上传换jar → restart → 验证(健康/CDC续传/job状态)。纯重构,行为不变,风险低。auto-rollback。
