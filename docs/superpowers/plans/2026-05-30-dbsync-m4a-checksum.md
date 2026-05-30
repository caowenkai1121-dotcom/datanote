# M4a 分片 checksum 深度对账 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 在行数对账之上加分片 checksum 深度对账:按主键分桶,每桶两端算 `BIT_XOR(CRC32(行))` 与计数比对,定位差异桶,证明同步内容一致(非仅行数)。

**Architecture:** 新增纯逻辑 `ChecksumSqlBuilder`(生成分桶 checksum SQL,可单测)。`DataReconciliationService` 加 `checksum(jobId)`:每表解析同步列与主键,两端各跑一条分桶聚合查询,比对每桶 count+xor,返回差异桶,写 `dn_task_execution(taskType=DataChecksum)`。`SyncJobController` 加端点,前端加按钮。**限 MySQL 协议族两端均支持 CRC32/BIT_XOR(目标为 Doris 时若不支持则该表降级提示);跨字段映射按解析后列对齐。**

**Tech Stack:** Java8、JUnit5、MySQL CRC32/BIT_XOR/CONCAT_WS。零新依赖。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sync/util/ChecksumSqlBuilder.java` | 分桶 checksum SQL 生成 | Create |
| `sync/service/DataReconciliationService.java` | checksum 对账 | Modify |
| `sync/controller/SyncJobController.java` | checksum 端点 | Modify |
| `src/main/resources/static/workspace.html` | checksum 按钮+结果 | Modify |

**契约:**
- `ChecksumSqlBuilder.build(db, table, syncColumns, pkColumns, bucketCount)`:
  `SELECT MOD(CRC32(CONCAT_WS('#', `pk1`,...)), N) AS bk, COUNT(*) cnt, BIT_XOR(CRC32(CONCAT_WS('#', IFNULL(`c1`,'\0'),...))) chk FROM `db`.`tbl` GROUP BY bk`
  列名经 SqlIdentifiers.quote;N 为桶数。
- `DataReconciliationService.checksum(jobId)`:返回 `{ok, tables:[{table, bucketCount, mismatchBuckets:[{bucket,sourceCnt,targetCnt,sourceChk,targetChk}], match}]}`。

---

## Task 1: ChecksumSqlBuilder(纯逻辑 TDD)

**Files:** Create `sync/util/ChecksumSqlBuilder.java` + test

- [ ] **Step 1: 失败测试**(JUnit5)

```java
package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class ChecksumSqlBuilderTest {
    @Test void singlePk() {
        String s = ChecksumSqlBuilder.build("db","t", Arrays.asList("id","name"), Arrays.asList("id"), 16);
        assertEquals("SELECT MOD(CRC32(CONCAT_WS('#', `id`)), 16) AS bk, COUNT(*) cnt, "
            + "BIT_XOR(CRC32(CONCAT_WS('#', IFNULL(`id`,'\\0'), IFNULL(`name`,'\\0')))) chk "
            + "FROM `db`.`t` GROUP BY bk", s);
    }
    @Test void multiPk() {
        String s = ChecksumSqlBuilder.build("db","t", Arrays.asList("a","b"), Arrays.asList("a","b"), 8);
        assertTrue(s.contains("CRC32(CONCAT_WS('#', `a`, `b`))"));
        assertTrue(s.contains("GROUP BY bk"));
        assertTrue(s.contains(", 8)"));
    }
}
```

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;
import java.util.List;
import java.util.stream.Collectors;
/** 分桶 checksum SQL:按主键 CRC32 取模分桶,每桶计数 + BIT_XOR(CRC32(行))。 */
public final class ChecksumSqlBuilder {
    private ChecksumSqlBuilder() {}
    public static String build(String db, String table, List<String> syncColumns, List<String> pkColumns, int bucketCount) {
        String pkConcat = pkColumns.stream().map(SqlIdentifiers::quote).collect(Collectors.joining(", "));
        String rowConcat = syncColumns.stream().map(c -> "IFNULL(" + SqlIdentifiers.quote(c) + ",'\\0')").collect(Collectors.joining(", "));
        String full = SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        return "SELECT MOD(CRC32(CONCAT_WS('#', " + pkConcat + ")), " + bucketCount + ") AS bk, COUNT(*) cnt, "
            + "BIT_XOR(CRC32(CONCAT_WS('#', " + rowConcat + "))) chk "
            + "FROM " + full + " GROUP BY bk";
    }
}
```

- [ ] **Step 4: 确认通过** `mvn -q -Dtest=ChecksumSqlBuilderTest test`
- [ ] **Step 5: Commit** `feat(sync-m4a): ChecksumSqlBuilder 分桶checksum SQL`

---

## Task 2: DataReconciliationService.checksum + 端点

**Files:** Modify `sync/service/DataReconciliationService.java`、`sync/controller/SyncJobController.java`

> **先读** DataReconciliationService(M3c 加的 reconcile/count/writeExec)与 FieldMappingResolver.resolve(多主键重载,返回 srcColumns/tgtColumns/pkSourceColumns/pkTargetColumns)。

- [ ] **Step 1: checksum 方法**

```java
    /** 分桶 checksum 深度对账:每表两端按主键分桶比对 count+xor,定位差异桶。 */
    public Map<String,Object> checksum(Long jobId) throws Exception {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在: " + jobId);
        int buckets = 16;
        DbConnector src = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector tgt = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        List<Map<String,Object>> tableResults = new ArrayList<>();
        boolean allMatch = true;
        for (TableSyncConfig tc : syncJobService.parseTables(job)) {
            Map<String,Object> tr = new LinkedHashMap<>();
            tr.put("table", tc.getSourceTable() + "->" + tc.getTargetTable());
            try {
                com.datanote.sync.connector.TableMeta meta = src.getTableMeta(job.getSourceDb(), tc.getSourceTable());
                com.datanote.sync.util.FieldMappingResolver.Resolved fm =
                    com.datanote.sync.util.FieldMappingResolver.resolve(tc, meta.getColumns(), meta.getPrimaryKeys());
                if (fm.pkSourceColumns.isEmpty()) { tr.put("match", false); tr.put("error", "无主键,不支持checksum"); allMatch=false; tableResults.add(tr); continue; }
                Map<Long,long[]> srcMap = runChecksum(src, job.getSourceDb(), tc.getSourceTable(), fm.srcColumns, fm.pkSourceColumns, buckets);
                Map<Long,long[]> tgtMap = runChecksum(tgt, job.getTargetDb(), tc.getTargetTable(), fm.tgtColumns, fm.pkTargetColumns, buckets);
                List<Map<String,Object>> mism = new ArrayList<>();
                java.util.Set<Long> allKeys = new java.util.TreeSet<>(); allKeys.addAll(srcMap.keySet()); allKeys.addAll(tgtMap.keySet());
                for (Long b : allKeys) {
                    long[] s = srcMap.getOrDefault(b, new long[]{0,0});
                    long[] t = tgtMap.getOrDefault(b, new long[]{0,0});
                    if (s[0]!=t[0] || s[1]!=t[1]) {
                        Map<String,Object> mb = new LinkedHashMap<>();
                        mb.put("bucket", b); mb.put("sourceCnt", s[0]); mb.put("targetCnt", t[0]);
                        mb.put("sourceChk", s[1]); mb.put("targetChk", t[1]);
                        mism.add(mb);
                    }
                }
                boolean match = mism.isEmpty();
                allMatch &= match;
                tr.put("bucketCount", buckets); tr.put("match", match); tr.put("mismatchBuckets", mism);
            } catch (Exception e) {
                tr.put("match", false); tr.put("error", e.getMessage()); allMatch = false;
            }
            tableResults.add(tr);
        }
        writeChecksumExec(jobId, allMatch, tableResults);
        Map<String,Object> r = new LinkedHashMap<>(); r.put("ok", allMatch); r.put("tables", tableResults);
        return r;
    }
    private Map<Long,long[]> runChecksum(DbConnector conn, String db, String table, List<String> cols, List<String> pks, int buckets) throws Exception {
        String sql = com.datanote.sync.util.ChecksumSqlBuilder.build(db, table, cols, pks, buckets);
        Map<Long,long[]> m = new LinkedHashMap<>();
        try (java.sql.Connection c = conn.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) { m.put(rs.getLong("bk"), new long[]{rs.getLong("cnt"), rs.getLong("chk")}); }
        }
        return m;
    }
    private void writeChecksumExec(Long jobId, boolean ok, List<Map<String,Object>> rows) {
        try {
            DnTaskExecution e = new DnTaskExecution();
            e.setSyncTaskId(jobId); e.setTaskType("DataChecksum"); e.setTriggerType("manual");
            e.setStatus(ok ? "SUCCESS" : "FAILED");
            e.setStartTime(java.time.LocalDateTime.now()); e.setEndTime(java.time.LocalDateTime.now()); e.setCreatedAt(java.time.LocalDateTime.now());
            e.setLog(com.alibaba.fastjson.JSON.toJSONString(rows));
            taskExecutionMapper.insert(e);
        } catch (Exception ex) { log.warn("checksum执行记录失败 jobId={}", jobId, ex); }
    }
```

- [ ] **Step 2: 端点**(SyncJobController)

```java
    @Operation(summary = "分片checksum深度对账")
    @PostMapping("/{id}/checksum")
    public R<java.util.Map<String,Object>> checksum(@PathVariable Long id) {
        try { return R.ok(reconciliationService.checksum(id)); }
        catch (Exception e) { return R.fail("checksum对账失败: " + e.getMessage()); }
    }
```

- [ ] **Step 3:** `mvn -q test` 全量不回归。
- [ ] **Step 4: Commit** `feat(sync-m4a): 分片checksum深度对账(分桶BIT_XOR比对)`

---

## Task 3: 前端 checksum 按钮

**Files:** Modify `src/main/resources/static/workspace.html`

> 沿用 M3c 行数对账(dbsyncOpenReconcile)模式。详情/操作菜单加"深度对账(checksum)"按钮:POST `/api/sync-job/{id}/checksum`,展示每表 match + 差异桶列表(bucket/源目标计数与校验和);全一致 toast"内容一致",有差异标红列差异桶。

- [ ] **Step 1:** 加按钮 + 结果渲染(可复用对账 modal)。
- [ ] **Step 2:** `mvn -q -Dtest=WorkspaceDbSyncUiTest test`(可加断言)。
- [ ] **Step 3: Commit** `feat(sync-m4a): 前端 checksum 深度对账按钮`

---

## Self-Review

- **Spec 覆盖**: 分片 checksum(B30)。✅
- **类型一致**: ChecksumSqlBuilder.build(db,table,List,List,int)、checksum(jobId)、runChecksum 返回 Map<Long,long[]> 全程一致。✅
- **限制**: 依赖 MySQL CRC32/BIT_XOR/CONCAT_WS;目标 Doris 若不支持则该表 catch 记 error(不崩整任务)。无主键表跳过。字段映射按解析后列对齐(源 srcColumns/目标 tgtColumns 同序,值相等则 CRC32 相等)。
- **回归保护**: 纯新增端点+方法,不改现有对账/同步逻辑。

---

## 部署(M4a)

无 DDL(复用 dn_task_execution)。`mvn -o clean package` → 分块上传换jar → restart → 验证(健康/CDC续传/job状态/checksum 端点)。auto-rollback。
