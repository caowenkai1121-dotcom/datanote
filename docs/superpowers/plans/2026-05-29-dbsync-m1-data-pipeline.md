# M1 数据加工管道（轻量 ETL）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给关系库同步管道加值级加工能力——行过滤 WHERE 下推、空值/默认值、内置转换函数、PII 脱敏、前后置 SQL，把"读列→原样写列"的哑管道升级为轻量 ETL。

**Architecture:** 新增 6 个纯逻辑工具类（`FilterExpressionBuilder`/`NullValueHandler`/`ValueTransformer`/`PiiMasker`/`RowValueProcessor`/`SqlExecutor`），全部可独立单测。`FieldMappingResolver.Resolved` 扩展 `srcToFieldMapping`，`FullSyncEngine`/`IncrementalSyncEngine` 读取循环改为经 `RowValueProcessor` 统一加工（一个入口，避免行级散插），`build*PageSql` 增 `extraWhere` 参数下推过滤。前后置 SQL 在 setAutoCommit 后 / 末页后执行。**本里程碑不引入 SqlDialect 重构（推迟到 M2）**，WHERE 下推直接扩展现有方法。

**Tech Stack:** Java 8、Spring Boot 2.7.18、JUnit（沿用 `src/test/java/com/datanote/sync` 现有风格）、FastJSON2（解析 filterExpression/transformExpression JSON）、JDK `MessageDigest`（SHA-256）。零新依赖。

**数据模型变更：**
- DDL `sql/25_sync_pipeline.sql`：`dn_sync_job` 加 `pre_sql`/`post_sql`（LONGTEXT）。
- `DnSyncJob` 实体：`preSql`/`postSql`。
- `TableSyncConfig` DTO（JSON，免 DDL）：`filterExpression`/`preSql`/`postSql`。
- `FieldMapping` DTO（JSON，免 DDL）：`defaultValue`/`nullHandling`/`transformExpression`/`maskingType`/`maskingSalt`。
- `SyncContext`：`globalPreSql`/`globalPostSql` + `getPreSql(table)`/`getPostSql(table)`。
- `FieldMappingResolver.Resolved`：`srcToFieldMapping`（Map<源列名,FieldMapping>）。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sql/25_sync_pipeline.sql` | dn_sync_job 加 pre_sql/post_sql 列 | Create |
| `model/DnSyncJob.java` | +preSql/postSql | Modify |
| `sync/dto/TableSyncConfig.java` | +filterExpression/preSql/postSql | Modify |
| `sync/dto/FieldMapping.java` | +defaultValue/nullHandling/transformExpression/maskingType/maskingSalt | Modify |
| `sync/dto/SyncContext.java` | +globalPreSql/globalPostSql + getPreSql/getPostSql | Modify |
| `sync/util/FieldMappingResolver.java` | Resolved +srcToFieldMapping | Modify |
| `sync/util/NullValueHandler.java` | 空值/默认值处理（PASSTHROUGH/REPLACE_WITH_DEFAULT/SKIP_ROW） | Create |
| `sync/util/ValueTransformer.java` | 转换函数（substring/pad/replace/dateFormat/upper/lower/trim） | Create |
| `sync/util/PiiMasker.java` | 脱敏（PHONE/EMAIL/IDCARD/HASH_SHA256/REDACT） | Create |
| `sync/util/RowValueProcessor.java` | 串 NullValueHandler→ValueTransformer→PiiMasker，一行一次 | Create |
| `sync/util/FilterExpressionBuilder.java` | filterExpression JSON → WHERE 片段 | Create |
| `sync/util/SqlExecutor.java` | 多语句 SQL 拆分逐条执行 | Create |
| `sync/connector/MysqlConnector.java` | build*PageSql 加 extraWhere | Modify |
| `sync/engine/FullSyncEngine.java` | 接 filter + RowValueProcessor + pre/post SQL | Modify |
| `sync/engine/IncrementalSyncEngine.java` | 同上 | Modify |
| `sync/service/SyncJobExecutor.java` | 构建 ctx 时注入 preSql/postSql + RowValueProcessor | Modify |
| 对应 `src/test/java/.../*Test.java` | 单测 | Create |

**统一契约（先读，后续任务都依赖）：**
- `nullHandling` 枚举字符串：`PASSTHROUGH`(默认/未配置)、`REPLACE_WITH_DEFAULT`、`SKIP_ROW`。
- `RowValueProcessor.process(List<String> srcColumns, Object[] raw)` 返回 `Object[]`（加工后值，长度同 raw）或 `null`（=跳过此行）。
- `transformExpression` JSON：`{"type":"substring","args":{"start":0,"length":10}}`；type ∈ substring/lpad/rpad/replace/dateFormat/upper/lower/trim。
- `maskingType` 字符串：PHONE/EMAIL/IDCARD/HASH_SHA256/REDACT；`maskingSalt` 仅 HASH_SHA256 用。
- `FilterExpressionBuilder.build(String filterExpression)` 返回 WHERE 片段（不含 "WHERE"，含括号包裹）或空串 `""`（无过滤）。

---

## Task 1: DDL + DnSyncJob 实体加 preSql/postSql

**Files:**
- Create: `sql/25_sync_pipeline.sql`
- Modify: `src/main/java/com/datanote/model/DnSyncJob.java`

- [ ] **Step 1: 写 DDL 文件**

```sql
-- M1 数据加工管道：dn_sync_job 加任务级前后置 SQL
USE datanote;
ALTER TABLE dn_sync_job
    ADD COLUMN IF NOT EXISTS pre_sql  LONGTEXT NULL COMMENT '任务级前置SQL(写入前执行,多语句分号分隔)',
    ADD COLUMN IF NOT EXISTS post_sql LONGTEXT NULL COMMENT '任务级后置SQL(写入后执行)';
```

- [ ] **Step 2: DnSyncJob 加字段**

在 `DnSyncJob.java` 现有"迭代V3 新增"块后追加：

```java
    // M1 数据加工管道
    private String preSql;   // 任务级前置SQL
    private String postSql;  // 任务级后置SQL
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add sql/25_sync_pipeline.sql src/main/java/com/datanote/model/DnSyncJob.java
git commit -m "feat(sync-m1): dn_sync_job 加 pre_sql/post_sql 字段与DDL"
```

---

## Task 2: TableSyncConfig + FieldMapping DTO 加字段

**Files:**
- Modify: `src/main/java/com/datanote/sync/dto/TableSyncConfig.java`
- Modify: `src/main/java/com/datanote/sync/dto/FieldMapping.java`

- [ ] **Step 1: TableSyncConfig 加字段**

在 `fields` 字段后追加（DTO 用 lombok `@Data`，无需 getter/setter）：

```java
    /** M1：行过滤 WHERE 条件 JSON，null/空=不过滤。 */
    private String filterExpression;
    /** M1：表级前置SQL，覆盖任务级 DnSyncJob.preSql（可选）。 */
    private String preSql;
    /** M1：表级后置SQL，覆盖任务级 DnSyncJob.postSql（可选）。 */
    private String postSql;
```

- [ ] **Step 2: FieldMapping 加字段**

在 `sync` 字段后追加：

```java
    /** M1：空值处理 PASSTHROUGH(默认)/REPLACE_WITH_DEFAULT/SKIP_ROW。 */
    private String nullHandling;
    /** M1：nullHandling=REPLACE_WITH_DEFAULT 时的替代值。 */
    private String defaultValue;
    /** M1：转换函数 JSON，如 {"type":"substring","args":{"start":0,"length":10}}。 */
    private String transformExpression;
    /** M1：脱敏类型 PHONE/EMAIL/IDCARD/HASH_SHA256/REDACT，null=不脱敏。 */
    private String maskingType;
    /** M1：HASH_SHA256 加盐。 */
    private String maskingSalt;
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/datanote/sync/dto/TableSyncConfig.java src/main/java/com/datanote/sync/dto/FieldMapping.java
git commit -m "feat(sync-m1): TableSyncConfig/FieldMapping 加加工配置字段"
```

---

## Task 3: SyncContext 前后置 SQL 取值

**Files:**
- Modify: `src/main/java/com/datanote/sync/dto/SyncContext.java`
- Test: `src/test/java/com/datanote/sync/dto/SyncContextSqlTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.dto;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SyncContextSqlTest {
    private TableSyncConfig tc(String src, String pre, String post) {
        TableSyncConfig t = new TableSyncConfig();
        t.setSourceTable(src); t.setPreSql(pre); t.setPostSql(post);
        return t;
    }

    @Test
    public void tableLevelOverridesGlobal() {
        SyncContext ctx = new SyncContext();
        ctx.setGlobalPreSql("GP"); ctx.setGlobalPostSql("GQ");
        assertEquals("TP", ctx.getPreSql(tc("t","TP",null)));
        assertEquals("TQ", ctx.getPostSql(tc("t",null,"TQ")));
    }

    @Test
    public void fallbackToGlobalWhenTableBlank() {
        SyncContext ctx = new SyncContext();
        ctx.setGlobalPreSql("GP"); ctx.setGlobalPostSql("GQ");
        assertEquals("GP", ctx.getPreSql(tc("t",null,null)));
        assertEquals("GP", ctx.getPreSql(tc("t","  ",null)));
        assertEquals("GQ", ctx.getPostSql(tc("t",null,"")));
    }

    @Test
    public void nullWhenBothBlank() {
        SyncContext ctx = new SyncContext();
        assertNull(ctx.getPreSql(tc("t",null,null)));
        assertNull(ctx.getPostSql(tc("t",null,null)));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=SyncContextSqlTest test`
Expected: 编译失败（getGlobalPreSql/getPreSql 不存在）

- [ ] **Step 3: 实现**

在 `SyncContext` 加字段（`markSyncTs` 附近）与方法：

```java
    /** M1：任务级前置/后置 SQL（表级未配置时回退此值）。 */
    private String globalPreSql;
    private String globalPostSql;

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** 取表级 preSql，空则回退任务级；都空返回 null。 */
    public String getPreSql(TableSyncConfig tc) {
        String t = tc == null ? null : tc.getPreSql();
        if (!blank(t)) return t;
        return blank(globalPreSql) ? null : globalPreSql;
    }

    public String getPostSql(TableSyncConfig tc) {
        String t = tc == null ? null : tc.getPostSql();
        if (!blank(t)) return t;
        return blank(globalPostSql) ? null : globalPostSql;
    }
```

> 注：`@Data` 会为 `globalPreSql/globalPostSql` 生成 getter/setter，与上面手写的 `getPreSql(TableSyncConfig)` 不冲突（参数不同）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=SyncContextSqlTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/dto/SyncContext.java src/test/java/com/datanote/sync/dto/SyncContextSqlTest.java
git commit -m "feat(sync-m1): SyncContext 前后置SQL取值(表级覆盖任务级)"
```

---

## Task 4: FieldMappingResolver.Resolved 扩展 srcToFieldMapping

**Files:**
- Modify: `src/main/java/com/datanote/sync/util/FieldMappingResolver.java`
- Test: `src/test/java/com/datanote/sync/util/FieldMappingResolverSrcMapTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class FieldMappingResolverSrcMapTest {
    private FieldMapping fm(String s, String t, boolean sync, String nh) {
        FieldMapping f = new FieldMapping();
        f.setSource(s); f.setTarget(t); f.setSync(sync); f.setNullHandling(nh);
        return f;
    }

    @Test
    public void emptyFieldsGivesEmptyMap() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        FieldMappingResolver.Resolved r =
            FieldMappingResolver.resolve(tc, Arrays.asList("id","name"), "id");
        assertNotNull(r.srcToFieldMapping);
        assertTrue(r.srcToFieldMapping.isEmpty());
    }

    @Test
    public void mapKeyedBySourceColumn() {
        TableSyncConfig tc = new TableSyncConfig(); tc.setSourceTable("t");
        tc.setFields(Arrays.asList(
            fm("id","id",true,null),
            fm("name","nm",true,"SKIP_ROW")));
        FieldMappingResolver.Resolved r =
            FieldMappingResolver.resolve(tc, Arrays.asList("id","name"), "id");
        assertEquals("SKIP_ROW", r.srcToFieldMapping.get("name").getNullHandling());
        assertTrue(r.srcToFieldMapping.containsKey("id"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=FieldMappingResolverSrcMapTest test`
Expected: 编译失败（`srcToFieldMapping` 不存在）

- [ ] **Step 3: 实现**

`Resolved` 加字段（保留 `pkTarget` 不动，M2 再上 keysetColumns）：

```java
        public final java.util.Map<String, FieldMapping> srcToFieldMapping;

        public Resolved(List<String> srcColumns, List<String> tgtColumns, String pkTarget,
                        java.util.Map<String, FieldMapping> srcToFieldMapping) {
            this.srcColumns = srcColumns;
            this.tgtColumns = tgtColumns;
            this.pkTarget = pkTarget;
            this.srcToFieldMapping = srcToFieldMapping == null
                ? java.util.Collections.emptyMap() : srcToFieldMapping;
        }
```

`resolve()` 两个 return 改为传入 map：
- fields 空：`new Resolved(allCols, allCols, pkSource, java.util.Collections.emptyMap())`
- fields 非空：构建 `Map<String,FieldMapping> src2fm = new LinkedHashMap<>();` 遍历 `fields`，对 `sync==true && source 非空` 的 `fm` 放入 `src2fm.put(fm.getSource(), fm)`，最后 `new Resolved(srcColumns, tgtColumns, srcToTgt.get(pkSource), src2fm)`。

- [ ] **Step 4: 运行确认通过（含原有测试不回归）**

Run: `mvn -q -o -Dtest=FieldMappingResolverSrcMapTest,FieldMappingResolverTest test`
Expected: PASS（注意旧测试若直接 `new Resolved(a,b,c)` 需同步改为 4 参，或保留旧 3 参构造器委托）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/FieldMappingResolver.java src/test/java/com/datanote/sync/util/FieldMappingResolverSrcMapTest.java
git commit -m "feat(sync-m1): Resolved 暴露 srcToFieldMapping(源列->映射)"
```

---

## Task 5: NullValueHandler（纯逻辑 TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/NullValueHandler.java`
- Test: `src/test/java/com/datanote/sync/util/NullValueHandlerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import org.junit.Test;
import static org.junit.Assert.*;

public class NullValueHandlerTest {
    private FieldMapping fm(String nh, String def) {
        FieldMapping f = new FieldMapping(); f.setNullHandling(nh); f.setDefaultValue(def); return f;
    }
    @Test public void nonNullPassthrough() {
        assertEquals("x", NullValueHandler.handle("x", fm("REPLACE_WITH_DEFAULT","D")));
    }
    @Test public void nullPassthroughByDefault() {
        assertNull(NullValueHandler.handle(null, null));
        assertNull(NullValueHandler.handle(null, fm("PASSTHROUGH",null)));
    }
    @Test public void nullReplacedWithDefault() {
        assertEquals("D", NullValueHandler.handle(null, fm("REPLACE_WITH_DEFAULT","D")));
    }
    @Test public void nullSkipRowReturnsSentinel() {
        assertSame(NullValueHandler.SKIP, NullValueHandler.handle(null, fm("SKIP_ROW",null)));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=NullValueHandlerTest test`
Expected: 编译失败

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;

/** 空值处理：按 FieldMapping.nullHandling 决定原样/替默认/跳行。 */
public final class NullValueHandler {
    /** 跳行哨兵：handle 返回此对象表示整行应被跳过。 */
    public static final Object SKIP = new Object();

    private NullValueHandler() {}

    public static Object handle(Object value, FieldMapping fm) {
        if (value != null || fm == null) return value;
        String nh = fm.getNullHandling();
        if ("REPLACE_WITH_DEFAULT".equalsIgnoreCase(nh)) return fm.getDefaultValue();
        if ("SKIP_ROW".equalsIgnoreCase(nh)) return SKIP;
        return null; // PASSTHROUGH / 未配置
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=NullValueHandlerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/NullValueHandler.java src/test/java/com/datanote/sync/util/NullValueHandlerTest.java
git commit -m "feat(sync-m1): NullValueHandler 空值/默认值/跳行"
```

---

## Task 6: ValueTransformer（纯逻辑 TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/ValueTransformer.java`
- Test: `src/test/java/com/datanote/sync/util/ValueTransformerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ValueTransformerTest {
    @Test public void nullExprPassthrough() {
        assertEquals("abc", ValueTransformer.transform("abc", null));
        assertEquals("abc", ValueTransformer.transform("abc", ""));
    }
    @Test public void nullValuePassthrough() {
        assertNull(ValueTransformer.transform(null, "{\"type\":\"upper\"}"));
    }
    @Test public void upperLowerTrim() {
        assertEquals("ABC", ValueTransformer.transform("abc", "{\"type\":\"upper\"}"));
        assertEquals("abc", ValueTransformer.transform("ABC", "{\"type\":\"lower\"}"));
        assertEquals("ab", ValueTransformer.transform(" ab ", "{\"type\":\"trim\"}"));
    }
    @Test public void substring() {
        assertEquals("hel",
            ValueTransformer.transform("hello", "{\"type\":\"substring\",\"args\":{\"start\":0,\"length\":3}}"));
    }
    @Test public void substringOutOfRangeClamped() {
        assertEquals("lo",
            ValueTransformer.transform("hello", "{\"type\":\"substring\",\"args\":{\"start\":3,\"length\":99}}"));
    }
    @Test public void replace() {
        assertEquals("a-b",
            ValueTransformer.transform("a_b", "{\"type\":\"replace\",\"args\":{\"from\":\"_\",\"to\":\"-\"}}"));
    }
    @Test public void lpad() {
        assertEquals("007",
            ValueTransformer.transform("7", "{\"type\":\"lpad\",\"args\":{\"len\":3,\"pad\":\"0\"}}"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=ValueTransformerTest test`
Expected: 编译失败

- [ ] **Step 3: 实现**

用 FastJSON2（`com.alibaba.fastjson2.JSONObject`，项目已用，见其他类 import）。值统一按字符串转换，非 String 入参先 `String.valueOf`。

```java
package com.datanote.sync.util;

import com.alibaba.fastjson2.JSONObject;

/** 内置转换函数：按 transformExpression JSON 对单值做轻量清洗。null 值/空表达式原样返回。 */
public final class ValueTransformer {
    private ValueTransformer() {}

    public static Object transform(Object value, String expr) {
        if (value == null || expr == null || expr.trim().isEmpty()) return value;
        JSONObject o = JSONObject.parseObject(expr);
        String type = o.getString("type");
        if (type == null) return value;
        JSONObject a = o.getJSONObject("args");
        String s = String.valueOf(value);
        switch (type.toLowerCase()) {
            case "upper": return s.toUpperCase();
            case "lower": return s.toLowerCase();
            case "trim":  return s.trim();
            case "substring": {
                int start = a.getIntValue("start", 0);
                int len = a.getIntValue("length", s.length());
                if (start < 0) start = 0;
                if (start > s.length()) start = s.length();
                int end = Math.min(s.length(), start + Math.max(0, len));
                return s.substring(start, end);
            }
            case "replace": return s.replace(a.getString("from"), a.getString("to"));
            case "lpad": {
                int len = a.getIntValue("len", s.length());
                String pad = a.getString("pad"); if (pad == null || pad.isEmpty()) pad = " ";
                StringBuilder b = new StringBuilder();
                while (b.length() + s.length() < len) b.append(pad);
                return b.append(s).toString();
            }
            case "rpad": {
                int len = a.getIntValue("len", s.length());
                String pad = a.getString("pad"); if (pad == null || pad.isEmpty()) pad = " ";
                StringBuilder b = new StringBuilder(s);
                while (b.length() < len) b.append(pad);
                return b.toString();
            }
            case "dateformat": {
                // value 为 java.sql.Timestamp/Date/String → 按 fmt 格式化
                String fmt = a.getString("format");
                java.util.Date d = (value instanceof java.util.Date) ? (java.util.Date) value
                        : new java.util.Date(Long.parseLong(s));
                return new java.text.SimpleDateFormat(fmt).format(d);
            }
            default: return value;
        }
    }
}
```

> 注：`getIntValue(key, def)` 是 FastJSON2 API；若版本无双参重载，改用 `a.containsKey(k)?a.getIntValue(k):def`。subagent 实现时按实际 FastJSON2 版本调整，测试为准。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=ValueTransformerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/ValueTransformer.java src/test/java/com/datanote/sync/util/ValueTransformerTest.java
git commit -m "feat(sync-m1): ValueTransformer 内置转换函数"
```

---

## Task 7: PiiMasker（纯逻辑 TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/PiiMasker.java`
- Test: `src/test/java/com/datanote/sync/util/PiiMaskerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PiiMaskerTest {
    @Test public void nullTypePassthrough() {
        assertEquals("x", PiiMasker.mask("x", null, null));
    }
    @Test public void nullValuePassthrough() {
        assertNull(PiiMasker.mask(null, "PHONE", null));
    }
    @Test public void phone() {
        assertEquals("138****8000", PiiMasker.mask("13812348000", "PHONE", null));
    }
    @Test public void email() {
        assertEquals("a***@b.com", PiiMasker.mask("abc@b.com", "EMAIL", null));
    }
    @Test public void idcard() {
        assertEquals("110***********1234", PiiMasker.mask("110101199001011234", "IDCARD", null));
    }
    @Test public void redact() {
        assertEquals("***", PiiMasker.mask("anything", "REDACT", null));
    }
    @Test public void hashDeterministic() {
        Object h1 = PiiMasker.mask("secret", "HASH_SHA256", "salt");
        Object h2 = PiiMasker.mask("secret", "HASH_SHA256", "salt");
        assertEquals(h1, h2);
        assertNotEquals("secret", h1);
        assertEquals(64, String.valueOf(h1).length()); // hex 64
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=PiiMaskerTest test`
Expected: 编译失败

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** PII 脱敏：掩码/确定性哈希/整列遮蔽。null 值或 null 类型原样返回。 */
public final class PiiMasker {
    private PiiMasker() {}

    public static Object mask(Object value, String type, String salt) {
        if (value == null || type == null || type.trim().isEmpty()) return value;
        String s = String.valueOf(value);
        switch (type.toUpperCase()) {
            case "PHONE":
                return s.length() < 7 ? "****" : s.substring(0, 3) + "****" + s.substring(s.length() - 4);
            case "EMAIL": {
                int at = s.indexOf('@');
                if (at <= 1) return s.length() <= 1 ? "***" : s.charAt(0) + "***" + s.substring(at < 0 ? s.length() : at);
                return s.charAt(0) + "***" + s.substring(at);
            }
            case "IDCARD":
                return s.length() < 7 ? "***" : s.substring(0, 3) + repeat("*", s.length() - 7) + s.substring(s.length() - 4);
            case "REDACT":
                return "***";
            case "HASH_SHA256":
                return sha256((salt == null ? "" : salt) + s);
            default:
                return value;
        }
    }

    private static String repeat(String c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    private static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=PiiMaskerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/PiiMasker.java src/test/java/com/datanote/sync/util/PiiMaskerTest.java
git commit -m "feat(sync-m1): PiiMasker 脱敏(掩码/SHA256/遮蔽)"
```

---

## Task 8: RowValueProcessor（串联管道 TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/RowValueProcessor.java`
- Test: `src/test/java/com/datanote/sync/util/RowValueProcessorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RowValueProcessorTest {
    private FieldMapping fm(String src, String nh, String def, String tx, String mask) {
        FieldMapping f = new FieldMapping();
        f.setSource(src); f.setSync(true);
        f.setNullHandling(nh); f.setDefaultValue(def);
        f.setTransformExpression(tx); f.setMaskingType(mask);
        return f;
    }

    @Test public void emptyMapIsNoop() {
        RowValueProcessor p = new RowValueProcessor(Collections.emptyMap());
        Object[] in = {1, "a", null};
        Object[] out = p.process(Arrays.asList("id","name","x"), in);
        assertArrayEquals(in, out);
    }

    @Test public void defaultAndUpperAndMask() {
        Map<String,FieldMapping> m = new HashMap<>();
        m.put("name", fm("name", null, null, "{\"type\":\"upper\"}", null));
        m.put("city", fm("city", "REPLACE_WITH_DEFAULT", "UNKNOWN", null, null));
        m.put("phone", fm("phone", null, null, null, "PHONE"));
        RowValueProcessor p = new RowValueProcessor(m);
        Object[] out = p.process(Arrays.asList("name","city","phone"),
                new Object[]{"bob", null, "13812348000"});
        assertEquals("BOB", out[0]);
        assertEquals("UNKNOWN", out[1]);
        assertEquals("138****8000", out[2]);
    }

    @Test public void skipRowReturnsNull() {
        Map<String,FieldMapping> m = new HashMap<>();
        m.put("name", fm("name", "SKIP_ROW", null, null, null));
        RowValueProcessor p = new RowValueProcessor(m);
        assertNull(p.process(Arrays.asList("name"), new Object[]{null}));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=RowValueProcessorTest test`
Expected: 编译失败

- [ ] **Step 3: 实现**

处理顺序：空值处理 → 转换 → 脱敏。空值替默认后仍可被转换/脱敏；SKIP_ROW 立即返回 null。

```java
package com.datanote.sync.util;

import com.datanote.sync.dto.FieldMapping;

import java.util.List;
import java.util.Map;

/** 行值加工管道：逐列 空值处理→转换→脱敏。空映射=直通。SKIP_ROW 返回 null（跳行）。 */
public final class RowValueProcessor {
    private final Map<String, FieldMapping> srcToFieldMapping;
    private final boolean active;

    public RowValueProcessor(Map<String, FieldMapping> srcToFieldMapping) {
        this.srcToFieldMapping = srcToFieldMapping;
        this.active = srcToFieldMapping != null && !srcToFieldMapping.isEmpty();
    }

    /** 返回加工后值数组；返回 null 表示此行应跳过。空管道直接返回原数组。 */
    public Object[] process(List<String> srcColumns, Object[] raw) {
        if (!active) return raw;
        Object[] out = new Object[raw.length];
        for (int i = 0; i < raw.length; i++) {
            FieldMapping fm = srcToFieldMapping.get(srcColumns.get(i));
            Object v = raw[i];
            if (fm == null) { out[i] = v; continue; }
            v = NullValueHandler.handle(v, fm);
            if (v == NullValueHandler.SKIP) return null;
            v = ValueTransformer.transform(v, fm.getTransformExpression());
            v = PiiMasker.mask(v, fm.getMaskingType(), fm.getMaskingSalt());
            out[i] = v;
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=RowValueProcessorTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/RowValueProcessor.java src/test/java/com/datanote/sync/util/RowValueProcessorTest.java
git commit -m "feat(sync-m1): RowValueProcessor 串联空值/转换/脱敏管道"
```

---

## Task 9: FilterExpressionBuilder（WHERE 下推 TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/FilterExpressionBuilder.java`
- Test: `src/test/java/com/datanote/sync/util/FilterExpressionBuilderTest.java`

**契约：** filterExpression JSON 形如
`{"logic":"AND","conditions":[{"column":"status","op":"=","value":"active"},{"column":"age","op":">","value":18}]}`。
op 白名单：`= <> > >= < <= LIKE`。column 必须 `SqlIdentifiers.isValid`。字符串值单引号转义；数字值校验为数值。返回 `(...)` 包裹的 WHERE 片段或 `""`。

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FilterExpressionBuilderTest {
    @Test public void blankGivesEmpty() {
        assertEquals("", FilterExpressionBuilder.build(null));
        assertEquals("", FilterExpressionBuilder.build(""));
    }
    @Test public void singleStringEq() {
        assertEquals("(`status` = 'active')",
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"status\",\"op\":\"=\",\"value\":\"active\"}]}"));
    }
    @Test public void numericGt() {
        assertEquals("(`age` > 18)",
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"age\",\"op\":\">\",\"value\":18}]}"));
    }
    @Test public void andCombine() {
        assertEquals("(`status` = 'active' AND `age` >= 18)",
            FilterExpressionBuilder.build("{\"logic\":\"AND\",\"conditions\":["
                + "{\"column\":\"status\",\"op\":\"=\",\"value\":\"active\"},"
                + "{\"column\":\"age\",\"op\":\">=\",\"value\":18}]}"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void illegalColumnRejected() {
        FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"a; DROP\",\"op\":\"=\",\"value\":\"1\"}]}");
    }
    @Test(expected = IllegalArgumentException.class)
    public void illegalOpRejected() {
        FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"a\",\"op\":\"OR 1=1\",\"value\":\"1\"}]}");
    }
    @Test public void stringValueEscaped() {
        assertEquals("(`name` = 'O''Brien')",
            FilterExpressionBuilder.build("{\"conditions\":[{\"column\":\"name\",\"op\":\"=\",\"value\":\"O'Brien\"}]}"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=FilterExpressionBuilderTest test`
Expected: 编译失败

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 行过滤表达式 → WHERE 片段。列名白名单校验、op 白名单、值转义，防注入。 */
public final class FilterExpressionBuilder {
    private static final Set<String> OPS = new HashSet<>(Arrays.asList("=", "<>", ">", ">=", "<", "<=", "LIKE"));
    private FilterExpressionBuilder() {}

    public static String build(String filterExpression) {
        if (filterExpression == null || filterExpression.trim().isEmpty()) return "";
        JSONObject o = JSONObject.parseObject(filterExpression);
        JSONArray conds = o.getJSONArray("conditions");
        if (conds == null || conds.isEmpty()) return "";
        String logic = o.getString("logic");
        logic = "OR".equalsIgnoreCase(logic) ? " OR " : " AND ";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < conds.size(); i++) {
            JSONObject c = conds.getJSONObject(i);
            String col = c.getString("column");
            String op = c.getString("op");
            if (!SqlIdentifiers.isValid(col)) throw new IllegalArgumentException("非法过滤列: " + col);
            if (op == null || !OPS.contains(op.toUpperCase())) throw new IllegalArgumentException("非法过滤操作符: " + op);
            if (i > 0) sb.append(logic);
            sb.append("`").append(col).append("` ").append(op.toUpperCase()).append(" ").append(literal(c.get("value")));
        }
        return sb.append(")").toString();
    }

    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=FilterExpressionBuilderTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/FilterExpressionBuilder.java src/test/java/com/datanote/sync/util/FilterExpressionBuilderTest.java
git commit -m "feat(sync-m1): FilterExpressionBuilder 行过滤WHERE生成(防注入)"
```

---

## Task 10: SqlExecutor（前后置 SQL TDD）

**Files:**
- Create: `src/main/java/com/datanote/sync/util/SqlExecutor.java`
- Test: `src/test/java/com/datanote/sync/util/SqlExecutorTest.java`

**契约：** `splitStatements(String sql)` 把多语句按 `;` 拆分，去掉空白与单行注释行（以 `--` 开头），返回非空语句列表。`execute(Connection, sql)` 逐条 `Statement.execute`。M1 仅对 `splitStatements` 写单测（纯逻辑），`execute` 由引擎集成测试覆盖。

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.util;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SqlExecutorTest {
    @Test public void blankGivesEmpty() {
        assertTrue(SqlExecutor.splitStatements(null).isEmpty());
        assertTrue(SqlExecutor.splitStatements("   ").isEmpty());
    }
    @Test public void singleStatement() {
        List<String> s = SqlExecutor.splitStatements("TRUNCATE TABLE t");
        assertEquals(1, s.size());
        assertEquals("TRUNCATE TABLE t", s.get(0));
    }
    @Test public void multiSplitOnSemicolon() {
        List<String> s = SqlExecutor.splitStatements("TRUNCATE t1;\nDELETE FROM t2 WHERE x=1;");
        assertEquals(2, s.size());
        assertEquals("DELETE FROM t2 WHERE x=1", s.get(1));
    }
    @Test public void commentLinesSkipped() {
        List<String> s = SqlExecutor.splitStatements("-- clean\nTRUNCATE t1;\n-- next\nDELETE FROM t2");
        assertEquals(2, s.size());
        assertEquals("TRUNCATE t1", s.get(0));
        assertEquals("DELETE FROM t2", s.get(1));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=SqlExecutorTest test`
Expected: 编译失败

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 多语句 SQL 拆分与执行（前后置 SQL 用）。 */
public final class SqlExecutor {
    private SqlExecutor() {}

    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null || sql.trim().isEmpty()) return out;
        for (String part : sql.split(";")) {
            StringBuilder b = new StringBuilder();
            for (String line : part.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                if (b.length() > 0) b.append(" ");
                b.append(t);
            }
            String stmt = b.toString().trim();
            if (!stmt.isEmpty()) out.add(stmt);
        }
        return out;
    }

    public static void execute(Connection conn, String sql) throws Exception {
        for (String stmt : splitStatements(sql)) {
            try (Statement s = conn.createStatement()) {
                s.execute(stmt);
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -o -Dtest=SqlExecutorTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/util/SqlExecutor.java src/test/java/com/datanote/sync/util/SqlExecutorTest.java
git commit -m "feat(sync-m1): SqlExecutor 多语句拆分与执行"
```

---

## Task 11: MysqlConnector build*PageSql 加 extraWhere

**Files:**
- Modify: `src/main/java/com/datanote/sync/connector/MysqlConnector.java`
- Test: `src/test/java/com/datanote/sync/connector/MysqlConnectorPageSqlTest.java`

> 兼容策略：保留原签名重载（委托新签名传 `null`），避免一次性改所有调用点。

- [ ] **Step 1: 写失败测试**

```java
package com.datanote.sync.connector;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MysqlConnectorPageSqlTest {
    @Test public void keysetNoFilterUnchanged() {
        String s = MysqlConnector.buildKeysetPageSql("db","t", Arrays.asList("id","n"), "id", false, "");
        assertEquals("SELECT `id`, `n` FROM `db`.`t` ORDER BY `id` ASC LIMIT ?", s);
    }
    @Test public void keysetWithCursorAndFilter() {
        String s = MysqlConnector.buildKeysetPageSql("db","t", Arrays.asList("id"), "id", true, "(`a` = 1)");
        assertEquals("SELECT `id` FROM `db`.`t` WHERE `id` > ? AND (`a` = 1) ORDER BY `id` ASC LIMIT ?", s);
    }
    @Test public void keysetFilterNoCursor() {
        String s = MysqlConnector.buildKeysetPageSql("db","t", Arrays.asList("id"), "id", false, "(`a` = 1)");
        assertEquals("SELECT `id` FROM `db`.`t` WHERE (`a` = 1) ORDER BY `id` ASC LIMIT ?", s);
    }
    @Test public void incrementalWithFilter() {
        String s = MysqlConnector.buildIncrementalPageSql("db","t", Arrays.asList("ts","id"), "ts", "id", true, "(`a` = 1)");
        assertEquals("SELECT `ts`, `id` FROM `db`.`t` WHERE `ts` >= ? AND (`a` = 1) ORDER BY `ts` ASC, `id` ASC LIMIT ?", s);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -o -Dtest=MysqlConnectorPageSqlTest test`
Expected: 编译失败（新 6 参重载不存在）

- [ ] **Step 3: 实现**

`buildKeysetPageSql` 加 `String extraWhere` 参数版，原 5 参版委托：

```java
    public static String buildKeysetPageSql(String db, String table, List<String> columns,
                                            String pkColumn, boolean hasCursor) {
        return buildKeysetPageSql(db, table, columns, pkColumn, hasCursor, null);
    }

    public static String buildKeysetPageSql(String db, String table, List<String> columns,
                                            String pkColumn, boolean hasCursor, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String pk = SqlIdentifiers.quote(pkColumn);
        boolean hasFilter = extraWhere != null && !extraWhere.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fullTable);
        if (hasCursor && hasFilter) {
            sql.append(" WHERE ").append(pk).append(" > ? AND ").append(extraWhere);
        } else if (hasCursor) {
            sql.append(" WHERE ").append(pk).append(" > ?");
        } else if (hasFilter) {
            sql.append(" WHERE ").append(extraWhere);
        }
        sql.append(" ORDER BY ").append(pk).append(" ASC LIMIT ?");
        return sql.toString();
    }
```

`buildIncrementalPageSql` 同理加 `extraWhere`（始终已有 WHERE，过滤用 ` AND extraWhere` 接在 where 之后、ORDER BY 之前），保留原 6 参重载委托新 7 参版：

```java
    public static String buildIncrementalPageSql(String db, String table, List<String> columns,
                                                 String incField, String pkColumn, boolean firstPage) {
        return buildIncrementalPageSql(db, table, columns, incField, pkColumn, firstPage, null);
    }

    public static String buildIncrementalPageSql(String db, String table, List<String> columns,
                                                 String incField, String pkColumn, boolean firstPage, String extraWhere) {
        String cols = columns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String fullTable = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        String inc = SqlIdentifiers.quote(incField);
        String pk = SqlIdentifiers.quote(pkColumn);
        String where = firstPage ? inc + " >= ?"
                : "(" + inc + " > ? OR (" + inc + " = ? AND " + pk + " > ?))";
        if (extraWhere != null && !extraWhere.trim().isEmpty()) {
            where = where + " AND " + extraWhere;
        }
        return "SELECT " + cols + " FROM " + fullTable + " WHERE " + where
                + " ORDER BY " + inc + " ASC, " + pk + " ASC LIMIT ?";
    }
```

- [ ] **Step 4: 运行确认通过（含原有 PageSql 测试不回归）**

Run: `mvn -q -o -Dtest=MysqlConnectorPageSqlTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/datanote/sync/connector/MysqlConnector.java src/test/java/com/datanote/sync/connector/MysqlConnectorPageSqlTest.java
git commit -m "feat(sync-m1): build*PageSql 支持 extraWhere 过滤下推"
```

---

## Task 12: FullSyncEngine 接入过滤 + 行加工 + 前后置 SQL

**Files:**
- Modify: `src/main/java/com/datanote/sync/engine/FullSyncEngine.java:45-150`

- [ ] **Step 1: 改 syncOneTable**

在 `FieldMappingResolver.resolve(...)` 后（约行 62）构建过滤与管道：

```java
        FieldMappingResolver.Resolved fm = FieldMappingResolver.resolve(tc, meta.getColumns(), pkColumn);
        List<String> srcColumns = fm.srcColumns;
        List<String> tgtColumns = fm.tgtColumns;
        // M1：行过滤下推 + 行值加工管道
        String extraWhere = com.datanote.sync.util.FilterExpressionBuilder.build(tc.getFilterExpression());
        com.datanote.sync.util.RowValueProcessor rowProc =
                new com.datanote.sync.util.RowValueProcessor(fm.srcToFieldMapping);
```

`setAutoCommit(false)` 后（约行 87）执行前置 SQL：

```java
            tgtConn.setAutoCommit(false);
            // M1：前置SQL（建临时表/清表/禁索引等）
            String preSql = ctx.getPreSql(tc);
            if (preSql != null) {
                ctx.log("INFO", "执行前置SQL: " + tc.getSourceTable());
                com.datanote.sync.util.SqlExecutor.execute(tgtConn, preSql);
                tgtConn.commit();
            }
```

`buildKeysetPageSql` 调用（约行 92）传 `extraWhere`：

```java
                    String pageSql = MysqlConnector.buildKeysetPageSql(
                            srcDb, tc.getSourceTable(), srcColumns, pkColumn, hasCursor, extraWhere);
```

读取循环（行 104-131）改为读 raw → 加工 → 跳行/写入，区分 rowsThisPage（读/游标/分页）与 rowsWritten（写）：

```java
                        try (ResultSet rs = readPs.executeQuery()) {
                            int rowsWritten = 0;
                            while (rs.next()) {
                                Object[] raw = new Object[srcColumns.size()];
                                for (int i = 0; i < srcColumns.size(); i++) raw[i] = rs.getObject(i + 1);
                                cursor = raw[pkIndex];      // 游标按原始主键推进（即使跳行也推进）
                                rowsThisPage++;
                                Object[] row = rowProc.process(srcColumns, raw);
                                if (row == null) continue;  // SKIP_ROW：计读不计写
                                for (int i = 0; i < srcColumns.size(); i++) writePs.setObject(i + 1, row[i]);
                                if (markTs) {
                                    writePs.setObject(srcColumns.size() + 1,
                                            new java.sql.Timestamp(System.currentTimeMillis()));
                                }
                                writePs.addBatch();
                                rowsWritten++;
                            }
                            if (rowsWritten > 0) {
                                try {
                                    writePs.executeBatch();
                                    tgtConn.commit();
                                } catch (Exception batchEx) {
                                    tgtConn.rollback();
                                    throw batchEx;
                                } finally {
                                    writePs.clearBatch();
                                }
                                ctx.getWriteCount().addAndGet(rowsWritten);
                            }
                        }
```

> 删除原先 `for(...) writePs.setObject(i+1, rs.getObject(i+1))` + `cursor = rs.getObject(pkIndex+1)` + `ctx.getWriteCount().addAndGet(rowsThisPage)` 旧块。`tableRead += rowsThisPage; ctx.getReadCount().addAndGet(rowsThisPage);` 保留不变。

末页 `break` 前（约行 142，循环正常结束后、`}` 关闭 writePs try 后）执行后置 SQL。最简：在 `while` 外、`}` 关闭外层 try 之前加：

```java
            // M1：后置SQL（建索引/刷新统计等）。在所有页写完后执行
            String postSql = ctx.getPostSql(tc);
            if (postSql != null) {
                ctx.log("INFO", "执行后置SQL: " + tc.getSourceTable());
                com.datanote.sync.util.SqlExecutor.execute(tgtConn, postSql);
                tgtConn.commit();
            }
```

放在 `try (PreparedStatement writePs...) {...}` 之后、`}` 关闭 `try(Connection...)` 之前（postSql 用同一 tgtConn）。

- [ ] **Step 2: 编译**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 跑现有同步测试不回归**

Run: `mvn -q -o -Dtest=*Sync*,*Engine* test`
Expected: PASS（若无引擎集成测试则仅编译通过即可，逻辑由纯逻辑类单测覆盖）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/datanote/sync/engine/FullSyncEngine.java
git commit -m "feat(sync-m1): FullSyncEngine 接过滤下推+行加工+前后置SQL"
```

---

## Task 13: IncrementalSyncEngine 接入过滤 + 行加工 + 前后置 SQL

**Files:**
- Modify: `src/main/java/com/datanote/sync/engine/IncrementalSyncEngine.java:44-182`

- [ ] **Step 1: 改 syncOneTable**

`resolve(...)` 后（约行 69）加：

```java
        String extraWhere = com.datanote.sync.util.FilterExpressionBuilder.build(tc.getFilterExpression());
        com.datanote.sync.util.RowValueProcessor rowProc =
                new com.datanote.sync.util.RowValueProcessor(fm.srcToFieldMapping);
```

`firstSql`/`nextSql` 构建（行 94-95）传 `extraWhere`：

```java
        String firstSql = MysqlConnector.buildIncrementalPageSql(srcDb, tc.getSourceTable(), srcColumns, incField, pkColumn, true, extraWhere);
        String nextSql  = MysqlConnector.buildIncrementalPageSql(srcDb, tc.getSourceTable(), srcColumns, incField, pkColumn, false, extraWhere);
```

`setAutoCommit(false)` 后（行 108）执行前置 SQL（同 Task12 片段，用 `ctx.getPreSql(tc)`）。

读取循环（行 131-162）改为 raw→加工→跳行/写，注意游标/maxValue 用 **原始** inc/pk 值推进（跳行也推进）：

```java
                        try (ResultSet rs = readPs.executeQuery()) {
                            int rowsWritten = 0;
                            while (rs.next()) {
                                Object[] raw = new Object[srcColumns.size()];
                                for (int i = 0; i < srcColumns.size(); i++) raw[i] = rs.getObject(i + 1);
                                lastInc = raw[incIndex];
                                lastPk = raw[pkIndex];
                                if (lastInc != null && strategy.compare(lastInc, maxValue) > 0) maxValue = lastInc;
                                rowsThisPage++;
                                Object[] row = rowProc.process(srcColumns, raw);
                                if (row == null) continue;  // SKIP_ROW：计读不计写，但游标/断点已按原始值推进
                                for (int i = 0; i < srcColumns.size(); i++) writePs.setObject(i + 1, row[i]);
                                if (markTs) {
                                    writePs.setObject(srcColumns.size() + 1,
                                            new java.sql.Timestamp(System.currentTimeMillis()));
                                }
                                writePs.addBatch();
                                rowsWritten++;
                            }
                            if (rowsWritten > 0) {
                                try {
                                    writePs.executeBatch();
                                    tgtConn.commit();
                                } catch (Exception batchEx) {
                                    tgtConn.rollback();
                                    throw batchEx;
                                } finally {
                                    writePs.clearBatch();
                                }
                                ctx.getWriteCount().addAndGet(rowsWritten);
                            }
                            // 游标推进：本页有返回行即推进（即使全跳行也要推进，否则死循环）
                            if (rowsThisPage > 0) { cursorInc = lastInc; cursorPk = lastPk; }
                        }
```

> 关键修正：原代码仅在 `rowsThisPage>0`（实为写入块内）推进 cursor，且用 `rowsThisPage` 计 writeCount。现拆分：cursor 推进条件改为"本页有返回行"，与是否写入解耦，**防全跳行页死循环**。`tableRead/readCount += rowsThisPage`、`firstPage=false`、末页 `break` 不变。

后置 SQL（行 174 关闭 writePs try 之后、关闭 Connection try 之前、`tc.setIncrementalValue` 之前）：用 `ctx.getPostSql(tc)`，同 Task12 片段。

- [ ] **Step 2: 编译**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 跑测试不回归**

Run: `mvn -q -o -Dtest=*Sync*,*Engine* test`
Expected: PASS / 编译通过

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/datanote/sync/engine/IncrementalSyncEngine.java
git commit -m "feat(sync-m1): IncrementalSyncEngine 接过滤+加工+前后置SQL+修跳行游标"
```

---

## Task 14: SyncJobExecutor 注入 preSql/postSql 到 SyncContext

**Files:**
- Modify: `src/main/java/com/datanote/sync/service/SyncJobExecutor.java`（构建 SyncContext 处）

> **先读** `SyncJobExecutor.java` 找到构建 `SyncContext`（`new SyncContext()` 或 setter 链）并从 `DnSyncJob` 取值填充的位置。M1 只需把 `job.getPreSql()`/`job.getPostSql()` 透传到 `ctx.setGlobalPreSql/PostSql`。表级 preSql/postSql/filterExpression/字段加工配置已随 `tableConfig`/`fields` JSON 反序列化进 `TableSyncConfig`/`FieldMapping`，引擎直接读，无需在此处额外处理。

- [ ] **Step 1: 在构建 ctx 处补两行**

在已有 `ctx.setMarkSyncTs(...)`/`ctx.setSyncTsField(...)` 附近追加：

```java
        ctx.setGlobalPreSql(job.getPreSql());
        ctx.setGlobalPostSql(job.getPostSql());
```

- [ ] **Step 2: 编译**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 全量编译 + 单测**

Run: `mvn -q -o test`
Expected: 全部 PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/datanote/sync/service/SyncJobExecutor.java
git commit -m "feat(sync-m1): SyncJobExecutor 透传任务级 pre/post SQL 到 ctx"
```

---

## Task 15: 前端 view-dbsync 配置入口（最小可用）

**Files:**
- Modify: `src/main/resources/static/workspace.html`（view-dbsync 任务编辑表单）

> **先读** workspace.html 中 view-dbsync 的任务保存表单与字段映射表格 JS，按现有风格追加。M1 只加最小录入：任务级 preSql/postSql 文本域、表级 filterExpression 输入、字段映射行追加 nullHandling/defaultValue/transformExpression/maskingType 列。保存时序列化进 tableConfig/fields JSON 与 job.preSql/postSql。

- [ ] **Step 1: 加任务级前后置 SQL 文本域**

在写模式/批大小等任务级配置区追加两个 `<textarea>` 绑定到保存 payload 的 `preSql`/`postSql`。

- [ ] **Step 2: 字段映射表格加列**

字段映射每行追加：空值处理下拉(PASSTHROUGH/REPLACE_WITH_DEFAULT/SKIP_ROW)、默认值输入、转换表达式输入、脱敏类型下拉(无/PHONE/EMAIL/IDCARD/HASH_SHA256/REDACT)，保存时写入该 field 的 `nullHandling`/`defaultValue`/`transformExpression`/`maskingType`。

- [ ] **Step 3: 表级过滤输入**

每个源表配置区加 `filterExpression`（JSON 文本域 + 占位提示示例），保存进对应 tableConfig 元素。

- [ ] **Step 4: 手测加载页面无 JS 报错**

Run: 本地启动或部署后浏览器打开 view-dbsync，新建/编辑任务，确认表单可填、保存 payload 含新字段。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/workspace.html
git commit -m "feat(sync-m1): view-dbsync 前端加加工/过滤/前后置SQL录入"
```

---

## Self-Review 结论

- **Spec 覆盖（M1 部分）**：批1 ①行过滤(Task9/11/12/13)、②前后置SQL(Task1/3/10/12/13/14)、③空值默认值(Task5/8)、④转换函数(Task6/8)、⑤PII脱敏(Task7/8)。数据模型 Task1-4 覆盖。前端 Task15。✅
- **占位符**：无 TBD/TODO；Task14/15 标注"先读"是因依赖未读文件的现状，已给出明确的最小改动契约与字段名，非占位。
- **类型一致性**：`RowValueProcessor.process(List<String>, Object[])` 全程一致；`NullValueHandler.SKIP`/`handle(Object,FieldMapping)`、`ValueTransformer.transform(Object,String)`、`PiiMasker.mask(Object,String,String)`、`FilterExpressionBuilder.build(String)`、`SqlExecutor.splitStatements/execute`、`build*PageSql` 新重载签名 —— 各任务引用一致。✅
- **依赖顺序**：Task1-4(模型) → 5-7(叶子纯逻辑) → 8(组合) → 9-11(独立纯逻辑) → 12-13(引擎，依赖 4/8/9/11) → 14(executor 依赖 3) → 15(前端)。无前向引用。✅

---

## 部署（M1 里程碑）

M1 全部任务完成且 `mvn -o test` 通过后：
1. `mvn -o clean package -DskipTests=false` 出 `target/datanote-1.0.0.jar`。
2. scp 分块上传替换服务器 `/opt/datanote/target/datanote-1.0.0.jar`（SSH 上行分块，见记忆 datanote-deploy-topology）。
3. 服务器 MySQL 跑 `sql/25_sync_pipeline.sql`。
4. `systemctl restart datanote`，`journalctl -u datanote -f` 看启动无异常。
5. 验证：建一个带过滤+脱敏+前后置SQL 的 MySQL→MySQL 全量任务，跑通，目标表数据符合加工预期。
