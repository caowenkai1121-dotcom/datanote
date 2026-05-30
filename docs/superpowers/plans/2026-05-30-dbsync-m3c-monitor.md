# M3c 行数对账 + 监控大盘 + 断点管理UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 加同步后行数对账(源/目标 count 比对)、任务监控大盘(状态/计数/CDC延迟一屏看)、断点管理UI(查看/重置 增量水位 与 全量chunk 断点)。

**Architecture:** 新增 `DataReconciliationService` 用 `MysqlConnector.buildCountSql(+filter)` 两端 count,写 `dn_task_execution(taskType=DataReconciliation)`。`SyncJobController` 加 `/dashboard`(聚合所有任务最新执行计数 + CDC 实时指标)、`/{id}/reconcile`、断点查看/重置端点。前端加大盘视图与断点面板。CDC offset 重置复用 M3a 的 `/api/cdc/{id}/reset`。**checksum(L)不在本轮。**

**Tech Stack:** Java8、JUnit5、MyBatis-Plus、FastJSON。零新依赖。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sync/connector/MysqlConnector.java` | buildCountSql 加 filter 重载 | Modify |
| `sync/service/DataReconciliationService.java` | 行数对账 | Create |
| `sync/service/SyncJobService.java` | dashboard 聚合 + 断点查看/重置 | Modify |
| `sync/controller/SyncJobController.java` | reconcile/dashboard/checkpoints 端点 | Modify |
| `src/main/resources/static/workspace.html` | 大盘视图 + 断点面板 | Modify |

**契约:**
- `MysqlConnector.buildCountSql(db, table, extraWhere)`:`SELECT COUNT(*) FROM db.table [WHERE ew]`;旧 2 参版委托(null)。
- `DataReconciliationService.reconcile(jobId)`:返回 `{ok, tables:[{table,sourceCount,targetCount,match}]}`,并写一条 dn_task_execution。
- dashboard 项:`{id,jobName,syncMode,status,scheduleStatus,lastReadCount,lastWriteCount,lastErrorCount,lastStatus,cdcLagMs,cdcEventsSeen}`。

---

## Task 1: 行数对账

**Files:** Modify `MysqlConnector.java`；Create `DataReconciliationService.java`；Modify `SyncJobController.java`；Test `MysqlConnectorCountTest.java`

- [ ] **Step 1: buildCountSql filter 重载(TDD)**

测试 `MysqlConnectorCountTest`(JUnit5):
```java
package com.datanote.sync.connector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class MysqlConnectorCountTest {
    @Test void noFilter() { assertEquals("SELECT COUNT(*) FROM `db`.`t`", MysqlConnector.buildCountSql("db","t","")); }
    @Test void withFilter() { assertEquals("SELECT COUNT(*) FROM `db`.`t` WHERE (`a`=1)", MysqlConnector.buildCountSql("db","t","(`a`=1)")); }
    @Test void legacy2arg() { assertEquals("SELECT COUNT(*) FROM `db`.`t`", MysqlConnector.buildCountSql("db","t")); }
}
```
实现:加 3 参重载,2 参版委托:
```java
    public static String buildCountSql(String db, String table) { return buildCountSql(db, table, null); }
    public static String buildCountSql(String db, String table, String extraWhere) {
        String base = "SELECT COUNT(*) FROM " + SqlIdentifiers.quote(db) + "." + SqlIdentifiers.quote(table);
        if (extraWhere != null && !extraWhere.trim().isEmpty()) base += " WHERE " + extraWhere;
        return base;
    }
```
（若旧 2 参版已存在,保留其实现并让其委托新 3 参版,避免重复。）

- [ ] **Step 2: DataReconciliationService**(@Service @RequiredArgsConstructor,注入 SyncJobService、DnTaskExecutionMapper)

```java
package com.datanote.sync.service;
// 行数对账:每表两端 COUNT 比对(叠加 filterExpression),写执行记录
@Service @RequiredArgsConstructor @Slf4j
public class DataReconciliationService {
    private final SyncJobService syncJobService;
    private final DnTaskExecutionMapper taskExecutionMapper;
    public Map<String,Object> reconcile(Long jobId) throws Exception {
        DnSyncJob job = syncJobService.getById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在: " + jobId);
        DbConnector src = syncJobService.buildConnector(job.getSourceDsId(), job.getSourceDb());
        DbConnector tgt = syncJobService.buildConnector(job.getTargetDsId(), job.getTargetDb());
        List<Map<String,Object>> rows = new ArrayList<>();
        boolean allMatch = true;
        for (TableSyncConfig tc : syncJobService.parseTables(job)) {
            String ew = com.datanote.sync.util.FilterExpressionBuilder.build(tc.getFilterExpression());
            long sc = count(src, job.getSourceDb(), tc.getSourceTable(), ew);
            long tcnt = count(tgt, job.getTargetDb(), tc.getTargetTable(), null); // 目标不套源过滤表达式(列名可能不同)
            boolean match = sc == tcnt;
            allMatch &= match;
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("table", tc.getSourceTable()+"->"+tc.getTargetTable());
            m.put("sourceCount", sc); m.put("targetCount", tcnt); m.put("match", match);
            rows.add(m);
        }
        writeExec(jobId, allMatch, rows);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("ok", allMatch); result.put("tables", rows);
        return result;
    }
    private long count(DbConnector conn, String db, String table, String ew) throws Exception {
        String sql = com.datanote.sync.connector.MysqlConnector.buildCountSql(db, table, ew);
        try (java.sql.Connection c = conn.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
    private void writeExec(Long jobId, boolean ok, List<Map<String,Object>> rows) {
        try {
            DnTaskExecution e = new DnTaskExecution();
            e.setSyncTaskId(jobId); e.setTaskType("DataReconciliation"); e.setTriggerType("manual");
            e.setStatus(ok ? "SUCCESS" : "FAILED");
            e.setStartTime(java.time.LocalDateTime.now()); e.setEndTime(java.time.LocalDateTime.now());
            e.setCreatedAt(java.time.LocalDateTime.now());
            e.setLog(com.alibaba.fastjson.JSON.toJSONString(rows));
            taskExecutionMapper.insert(e);
        } catch (Exception ex) { log.warn("对账执行记录失败 jobId={}", jobId, ex); }
    }
}
```
   (import DnSyncJob/TableSyncConfig/DbConnector/DnTaskExecution/DnTaskExecutionMapper/Service/RequiredArgsConstructor/Slf4j/List/Map/ArrayList/LinkedHashMap)

- [ ] **Step 3: 端点**（SyncJobController 注入 DataReconciliationService）

```java
    @Operation(summary = "行数对账(源/目标 count 比对)")
    @PostMapping("/{id}/reconcile")
    public R<java.util.Map<String,Object>> reconcile(@PathVariable Long id) {
        try { return R.ok(reconciliationService.reconcile(id)); }
        catch (Exception e) { return R.fail("对账失败: " + e.getMessage()); }
    }
```

- [ ] **Step 4:** `mvn -q -Dtest=MysqlConnectorCountTest test` + 全量 `mvn -q test`(controller 构造器加参补测试 mock)。
- [ ] **Step 5: Commit** `feat(sync-m3c): 行数对账(源/目标count比对)`

---

## Task 2: 监控大盘聚合端点 + 前端

**Files:** Modify `SyncJobService.java`、`SyncJobController.java`、`workspace.html`

- [ ] **Step 1: SyncJobService.dashboard()**（注入 DnTaskExecutionMapper、CdcEngineManager——**注意循环依赖**:CdcEngineManager 依赖 SyncJobService,故 SyncJobService 不能直接注入 CdcEngineManager。改为 dashboard 端点在 Controller 层组装:Controller 已可注入 CdcEngineManager）。

→ 改为在 **Controller** 实现 dashboard(避免循环依赖):
```java
    @Operation(summary = "监控大盘(所有任务状态+最新计数+CDC指标)")
    @GetMapping("/dashboard")
    public R<java.util.List<java.util.Map<String,Object>>> dashboard() {
        java.util.List<java.util.Map<String,Object>> list = new java.util.ArrayList<>();
        for (DnSyncJob job : syncJobService.list()) {
            java.util.Map<String,Object> m = new java.util.LinkedHashMap<>();
            m.put("id", job.getId()); m.put("jobName", job.getJobName());
            m.put("syncMode", job.getSyncMode()); m.put("status", job.getStatus());
            m.put("scheduleStatus", job.getScheduleStatus());
            DnTaskExecution last = taskExecutionMapper.selectOne(new LambdaQueryWrapper<DnTaskExecution>()
                .eq(DnTaskExecution::getSyncTaskId, job.getId()).eq(DnTaskExecution::getTaskType, "DbSync")
                .orderByDesc(DnTaskExecution::getId).last("LIMIT 1"));
            if (last != null) {
                m.put("lastStatus", last.getStatus()); m.put("lastReadCount", last.getReadCount());
                m.put("lastWriteCount", last.getWriteCount()); m.put("lastErrorCount", last.getErrorCount());
                m.put("lastTime", last.getStartTime());
            }
            if ("CDC".equalsIgnoreCase(job.getSyncMode())) {
                try { m.putAll(prefixCdc(cdcEngineManager.metrics(job.getId()))); } catch (Exception ignore) {}
            }
            list.add(m);
        }
        return R.ok(list);
    }
    private static java.util.Map<String,Object> prefixCdc(java.util.Map<String,Object> cdc) {
        java.util.Map<String,Object> m = new java.util.LinkedHashMap<>();
        if (cdc != null) { m.put("cdcRunning", cdc.get("running")); m.put("cdcLagMs", cdc.get("lagMs")); m.put("cdcEventsSeen", cdc.get("eventsSeen")); }
        return m;
    }
```
   Controller 注入 `CdcEngineManager cdcEngineManager`(@RequiredArgsConstructor 加 final;CdcEngineManager 是 bean,Controller 依赖它无环)。

- [ ] **Step 2: 前端大盘**:**先读** workspace.html 关系库同步视图(view-dbsync)。加一个"监控大盘"按钮/子视图:调 `/api/sync-job/dashboard`,表格展示每任务 状态/最新读写错误/CDC延迟,失败任务标红,定时(如 5s)轮询刷新(可复用现有 setInterval 模式,切走时清除)。沿用现有样式。

- [ ] **Step 3:** `mvn -q -Dtest=WorkspaceDbSyncUiTest,SyncJobControllerTest test` + `mvn -q compile`。
- [ ] **Step 4: Commit** `feat(sync-m3c): 监控大盘聚合端点+前端`

---

## Task 3: 断点管理(增量水位 + 全量chunk)端点 + 前端

**Files:** Modify `SyncJobService.java`、`SyncJobController.java`、`workspace.html`

- [ ] **Step 1: SyncJobService 加断点查看/重置**

```java
    /** 查看任务断点:增量每表水位 + 全量chunk游标。 */
    public Map<String,Object> getCheckpoints(Long jobId) {
        DnSyncJob job = getById(jobId);
        Map<String,Object> r = new LinkedHashMap<>();
        List<Map<String,Object>> incr = new ArrayList<>();
        if (job != null) {
            for (TableSyncConfig tc : parseTables(job)) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("table", tc.getSourceTable());
                m.put("incrementalField", tc.getIncrementalField());
                m.put("incrementalValue", tc.getIncrementalValue());
                incr.add(m);
            }
        }
        r.put("incremental", incr);
        // chunk
        r.put("chunk", chunkCheckpointMapper.selectList(new LambdaQueryWrapper<DnSyncChunkCheckpoint>()
            .eq(DnSyncChunkCheckpoint::getSyncJobId, jobId)));
        return r;
    }
    /** 重置某表增量水位(置空,下次从初值重扫)。 */
    public void resetIncremental(Long jobId, String sourceTable) {
        DnSyncJob job = getById(jobId);
        if (job == null) return;
        List<TableSyncConfig> tables = parseTables(job);
        for (TableSyncConfig tc : tables) {
            if (eq(tc.getSourceTable(), sourceTable)) tc.setIncrementalValue(null);
        }
        updateTableConfig(jobId, tables);
    }
```
   （chunkCheckpointMapper 已注入;resetChunk 复用既有 clearChunkCursor(jobId, table)）

- [ ] **Step 2: SyncJobController 端点**

```java
    @Operation(summary = "查看断点(增量水位+chunk)")
    @GetMapping("/{id}/checkpoints")
    public R<java.util.Map<String,Object>> checkpoints(@PathVariable Long id) { return R.ok(syncJobService.getCheckpoints(id)); }

    @Operation(summary = "重置增量水位")
    @PostMapping("/{id}/checkpoint/reset-incremental")
    public R<String> resetIncr(@PathVariable Long id, @RequestParam String table) {
        syncJobService.resetIncremental(id, table); return R.ok("已重置增量水位");
    }
    @Operation(summary = "重置全量chunk断点")
    @PostMapping("/{id}/checkpoint/reset-chunk")
    public R<String> resetChunk(@PathVariable Long id, @RequestParam String table) {
        syncJobService.clearChunkCursor(id, table); return R.ok("已重置chunk断点");
    }
```

- [ ] **Step 3: 前端断点面板**:详情抽屉加"断点"区块:调 `/api/sync-job/{id}/checkpoints`,展示增量每表水位 + chunk 游标;各带"重置"按钮(confirm 二次确认 → POST 对应 reset 端点 → 刷新)。CDC offset 重置链接到已有 CDC reset。

- [ ] **Step 4:** `mvn -q test` 全量 + `WorkspaceDbSyncUiTest`(可加断言)。
- [ ] **Step 5: Commit** `feat(sync-m3c): 断点管理(增量水位+chunk)端点+前端`

---

## Self-Review

- **Spec 覆盖**: 行数对账(Task1)、监控大盘(Task2)、断点管理UI(Task3,CDC offset 复用 M3a)。✅(分片checksum L 留后续)
- **循环依赖**: dashboard 放 Controller 层(注入 CdcEngineManager),SyncJobService 不依赖 CdcEngineManager。✅
- **类型一致**: buildCountSql(db,table,ew)、reconcile(jobId)、getCheckpoints/resetIncremental、dashboard 项键 全程一致。✅
- **回归保护**: buildCountSql 旧 2 参委托;新端点不改现有逻辑;对账只读 + 写独立 exec 记录。✅

---

## 部署(M3c 里程碑)

无 DDL(复用 dn_task_execution/dn_sync_chunk_checkpoint)。`mvn -o clean package` → 分块上传换jar → restart → 验证(健康/CDC续传/job状态/dashboard 端点返回)。auto-rollback。
