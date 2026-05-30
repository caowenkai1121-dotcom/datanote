# M4b CDC 增量快照 signal 表 + DDL 同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 给 CDC 加两项深水能力:① 增量快照 signal 表(无锁分块补数/重快照单表)② DDL 同步(源端 ADD COLUMN 后目标自动加列)。**两者均按 job 级开关 opt-in,默认关闭——部署后线上 CDC 行为零变化。**

**Architecture:** DnSyncJob 加 `incremental_snapshot_enabled`/`ddl_sync_enabled` 两开关(DDL 30)。`CdcSyncEngine.buildProps` 仅当 incrementalSnapshotEnabled 时加 `signal.data.collection`+`read.only`。`CdcEngineManager.triggerIncrementalSnapshot` 向源库 signal 表 INSERT execute-snapshot 信号。DDL 同步:`CdcSyncEngine.writeUpsert` 写前,ddlSyncEnabled 时检测 after-image 出现目标表没有的列→查源列类型(TypeMappingService 映射)→ALTER 目标 ADD COLUMN,失效列缓存。`CdcController` 加触发端点,前端加开关+按钮。

**Tech Stack:** Java8、JUnit5、Debezium1.9.7 DDD-3、TypeMappingService。零新依赖。

**安全前提:** signal 表 `dn_cdc_signal` 需在**源库**手工建(DDL 提供);两功能默认关,现有线上 CDC 任务不受影响。

---

## File Structure

| 文件 | 动作 |
|------|------|
| `sql/30_cdc_deep.sql` | dn_sync_job 加 2 开关 + dn_cdc_signal 建表(源库用) — Create |
| `model/DnSyncJob.java` | incrementalSnapshotEnabled/ddlSyncEnabled — Modify |
| `sync/engine/cdc/CdcSyncEngine.java` | signal props + DDL drift — Modify |
| `sync/service/CdcEngineManager.java` | triggerIncrementalSnapshot — Modify |
| `sync/controller/CdcController.java` | 增量快照触发端点 — Modify |
| `sync/util/SignalSqlBuilder.java` | signal INSERT SQL(纯逻辑) — Create |
| `src/main/resources/static/workspace.html` | 开关+触发按钮 — Modify |

---

## Task 1: DDL + 实体开关

- [ ] **Step 1: `sql/30_cdc_deep.sql`**(MySQL8 幂等加列 + signal 建表)

```sql
USE datanote;
-- dn_sync_job 两开关(幂等)
SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='incremental_snapshot_enabled');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN incremental_snapshot_enabled TINYINT DEFAULT 0 COMMENT ''CDC增量快照开关''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='ddl_sync_enabled');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN ddl_sync_enabled TINYINT DEFAULT 0 COMMENT ''CDC DDL同步开关''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
-- signal 表(Debezium DDD-3 要求:须建在【源库】且被捕获;此处给出结构,实际由运维在源库执行)
-- CREATE TABLE dn_cdc_signal (id VARCHAR(64) PRIMARY KEY, type VARCHAR(32) NOT NULL, data VARCHAR(2048) NULL) ENGINE=InnoDB;
```
  注：signal 建表语句注释保留(目标库不需要,源库由运维建)。

- [ ] **Step 2: DnSyncJob 加字段**

```java
    // M4b CDC 深水
    private Integer incrementalSnapshotEnabled;
    private Integer ddlSyncEnabled;
```

- [ ] **Step 3:** `mvn -q compile`。
- [ ] **Step 4: Commit** `feat(sync-m4b): DDL+实体 CDC增量快照/DDL同步开关`

---

## Task 2: SignalSqlBuilder + 增量快照触发

**Files:** Create `sync/util/SignalSqlBuilder.java`；Modify `CdcSyncEngine.java`、`CdcEngineManager.java`、`CdcController.java`；Test `SignalSqlBuilderTest.java`

- [ ] **Step 1: SignalSqlBuilder(纯逻辑 TDD)**

测试:
```java
package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
public class SignalSqlBuilderTest {
    @Test void executeSnapshot() {
        String s = SignalSqlBuilder.executeSnapshotData(Arrays.asList("db.t1","db.t2"));
        assertEquals("{\"data-collections\": [\"db.t1\",\"db.t2\"], \"type\": \"INCREMENTAL\"}", s);
    }
    @Test void insertSql() {
        assertEquals("INSERT INTO `srcdb`.`dn_cdc_signal` (`id`,`type`,`data`) VALUES (?,?,?)",
            SignalSqlBuilder.insertSql("srcdb"));
    }
}
```
实现:
```java
package com.datanote.sync.util;
import java.util.List;
import java.util.stream.Collectors;
/** Debezium 增量快照 signal 构造。 */
public final class SignalSqlBuilder {
    private SignalSqlBuilder() {}
    public static String insertSql(String signalDb) {
        return "INSERT INTO " + SqlIdentifiers.quote(signalDb) + "." + SqlIdentifiers.quote("dn_cdc_signal")
            + " (" + SqlIdentifiers.quote("id") + "," + SqlIdentifiers.quote("type") + "," + SqlIdentifiers.quote("data") + ") VALUES (?,?,?)";
    }
    /** execute-snapshot 信号的 data 列 JSON(INCREMENTAL 类型)。 */
    public static String executeSnapshotData(List<String> dataCollections) {
        String list = dataCollections.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
        return "{\"data-collections\": [" + list + "], \"type\": \"INCREMENTAL\"}";
    }
}
```
commit: `feat(sync-m4b): SignalSqlBuilder 增量快照信号SQL`

- [ ] **Step 2: CdcSyncEngine.buildProps 加 signal 配置**(仅开关开时)

在 buildProps 末尾(return 前):
```java
        if (job.getIncrementalSnapshotEnabled() != null && job.getIncrementalSnapshotEnabled() == 1) {
            String signalColl = job.getSourceDb() + ".dn_cdc_signal";
            props.setProperty("signal.data.collection", signalColl);
            // signal 表需在捕获范围:并入 table.include.list
            props.setProperty("table.include.list", buildTableIncludeList() + "," + signalColl);
            props.setProperty("read.only", "true");
        }
```
  (注意:buildTableIncludeList() 已有;开关关时 table.include.list 保持原样。)

- [ ] **Step 3: CdcEngineManager.triggerIncrementalSnapshot**

```java
    /** 触发指定表的增量快照(向源库 signal 表写 execute-snapshot 信号)。源库须已建 dn_cdc_signal 且任务开 incrementalSnapshotEnabled。 */
    public void triggerIncrementalSnapshot(Long jobId, List<String> sourceTables) {
        DnSyncJob job = syncJobMapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("任务不存在: " + jobId);
        if (job.getIncrementalSnapshotEnabled() == null || job.getIncrementalSnapshotEnabled() != 1)
            throw new IllegalStateException("该任务未开启增量快照");
        DnDatasource ds = datasourceMapper.selectById(job.getSourceDsId());
        List<String> colls = new ArrayList<>();
        for (String t : sourceTables) colls.add(job.getSourceDb() + "." + t);
        String data = com.datanote.sync.util.SignalSqlBuilder.executeSnapshotData(colls);
        String sql = com.datanote.sync.util.SignalSqlBuilder.insertSql(job.getSourceDb());
        // 用源数据源直连(非池),写 signal 行
        String url = "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort() + "/" + job.getSourceDb() + "?useSSL=false&allowPublicKeyRetrieval=true";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, ds.getUsername(), com.datanote.util.CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey));
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, "execute-snapshot");
            ps.setString(3, data);
            ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException("写 signal 失败(源库需已建 dn_cdc_signal): " + e.getMessage(), e); }
        log.info("已触发增量快照 jobId={} tables={}", jobId, sourceTables);
    }
```
  (UUID.randomUUID 在测试环境不可用?这是生产代码,运行期 OK;不写单测覆盖此方法,逻辑靠 SignalSqlBuilder 单测。)

- [ ] **Step 4: CdcController 端点**

```java
    @Operation(summary = "触发CDC增量快照(补数/重快照单表)")
    @PostMapping("/{jobId}/incremental-snapshot")
    public R<String> incrementalSnapshot(@PathVariable Long jobId, @RequestParam String tables) {
        try {
            cdcEngineManager.triggerIncrementalSnapshot(jobId, java.util.Arrays.asList(tables.split(",")));
            return R.ok("已触发增量快照");
        } catch (Exception e) { return R.fail("触发失败: " + e.getMessage()); }
    }
```

- [ ] **Step 5:** `mvn -q -Dtest=SignalSqlBuilderTest test` + 全量 `mvn -q test`(构造器未变,CdcController 已注入 cdcEngineManager)。
- [ ] **Step 6: Commit** `feat(sync-m4b): CDC增量快照signal配置+触发端点`

---

## Task 3: CDC DDL 同步(schema drift ADD COLUMN)

**Files:** Modify `CdcSyncEngine.java`

> **先读** CdcSyncEngine 的 writeUpsert、primaryKeysOf、pkCache、构造器(已有 sourceDs/connectionManager/targetConnector)。DDL 同步默认关(job.ddlSyncEnabled!=1 时完全跳过,行为同现状)。

- [ ] **Step 1: 目标列缓存 + 漂移检测**

加字段:`private final Map<String, java.util.Set<String>> targetColsCache = new ConcurrentHashMap<>();`
加方法:
```java
    /** 目标表列集合(缓存)。 */
    private java.util.Set<String> targetColumnsOf(String db, String table) throws Exception {
        String key = db + "." + table;
        java.util.Set<String> c = targetColsCache.get(key);
        if (c != null) return c;
        TableMeta meta = targetConnector.getTableMeta(db, table);
        java.util.Set<String> cols = new java.util.LinkedHashSet<>(meta.getColumns());
        targetColsCache.put(key, cols);
        return cols;
    }
    /** DDL 漂移:after 出现目标没有的列时,查源列类型,ALTER 目标 ADD COLUMN。仅 ddlSyncEnabled 时。 */
    private void syncDriftColumns(Connection conn, String db, String table, String sourceTable, java.util.Map<String,Object> after) throws Exception {
        if (job.getDdlSyncEnabled() == null || job.getDdlSyncEnabled() != 1 || after == null) return;
        java.util.Set<String> existing = targetColumnsOf(db, table);
        for (String col : after.keySet()) {
            if (!existing.contains(col)) {
                String colType = sourceColumnType(sourceTable, col); // 查源列类型
                String mapped = com.datanote.sync.schema.TypeMappingService.mapType(colType, targetConnector.getDatabaseType()); // 复用类型映射(确认方法名)
                String ddl = "ALTER TABLE " + com.datanote.sync.util.SqlIdentifiers.quote(db) + "." + com.datanote.sync.util.SqlIdentifiers.quote(table)
                    + " ADD COLUMN " + com.datanote.sync.util.SqlIdentifiers.quote(col) + " " + mapped + " NULL";
                try (PreparedStatement ps = conn.prepareStatement(ddl)) { ps.executeUpdate(); }
                broadcast("WARN", "DDL同步:目标表 " + table + " 新增列 " + col + " " + mapped);
                targetColsCache.remove(db + "." + table); // 失效缓存
            }
        }
    }
    /** 查源表某列的 COLUMN_TYPE(用源连接)。 */
    private String sourceColumnType(String sourceTable, String col) {
        try (Connection c = connectionManager.getConnection(job.getSourceDsId(), job.getSourceDb())) {
            String sql = "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, job.getSourceDb()); ps.setString(2, sourceTable); ps.setString(3, col);
                try (java.sql.ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
            }
        } catch (Exception ignore) {}
        return "TEXT"; // 查不到给宽松兜底
    }
```
  > **先读** TypeMappingService 确认实际映射方法签名(可能不是 mapType);若签名不同,按实际调用(目标=MySQL 时可直接用源 COLUMN_TYPE 原样,Doris 时才映射)。简化:目标=MySQL 直接用 colType;目标 DORIS/STARROCKS 才走 TypeMappingService。

- [ ] **Step 2: 在 applyChange 的 INSERT/UPDATE 写入前调用**

`applyChange` 里 INSERT(writeUpsert 前)与 UPDATE(writeUpsert(after) 前)调用 `syncDriftColumns(conn, targetDb, targetTable, change.sourceTable, projectByFields(change.after, tc))`。注意用投影后的 after(目标列名)与目标表对比。

- [ ] **Step 3:** `mvn -q -Dtest=CdcSyncEngine*Test test` 不回归 + `mvn -q compile`。
- [ ] **Step 4: Commit** `feat(sync-m4b): CDC DDL同步(schema drift自动加列,默认关)`

---

## Task 4: 前端开关 + 增量快照按钮

**Files:** Modify `src/main/resources/static/workspace.html`

> 沿用现有 CDC 操作与任务高级选项模式。

- [ ] **Step 1:** 任务高级选项加两个开关:增量快照(incrementalSnapshotEnabled)、DDL同步(ddlSyncEnabled),保存进 payload/回填/重置(沿用 M1/M2a 高级区模式)。
- [ ] **Step 2:** CDC 任务操作区加"增量快照"按钮(仅开关开时显示或始终显示):弹出输入源表名(逗号分隔)→ POST `/api/cdc/{jobId}/incremental-snapshot?tables=...` → toast。提示"需源库已建 dn_cdc_signal"。
- [ ] **Step 3:** `mvn -q -Dtest=WorkspaceDbSyncUiTest test`(可加断言)。
- [ ] **Step 4: Commit** `feat(sync-m4b): 前端 增量快照/DDL同步开关+触发`

---

## Self-Review

- **Spec 覆盖**: 增量快照 signal(B34, Task1/2/4)、DDL 同步(Task1/3/4)。✅
- **安全(关键)**: 两功能 job 级开关默认 0;buildProps signal 配置仅开关开时加(线上 CDC#2 未开→props 不变→行为零变化);DDL drift 开关关时直接 return(写路径零变化)。✅
- **类型一致**: SignalSqlBuilder.insertSql(db)/executeSnapshotData(List)、triggerIncrementalSnapshot(Long,List)、syncDriftColumns 全程一致。✅
- **限制**: signal 表须运维在源库手工建;DDL 同步仅 ADD COLUMN(MODIFY/DROP 不处理);增量快照需 read.only 模式(开关开时设)。

---

## 部署(M4b)

1. `mvn -o clean package`。
2. 分块上传 + 跑 `sql/30_cdc_deep.sql`(加 2 开关列;signal 建表在源库另行处理,本次不动源库)。
3. 备份+换jar+restart+**重点验证:线上 CDC#2(未开两开关)行为不变、从 binlog 续跑、props 无 signal/read.only**;audit/job 状态正常。auto-rollback。
