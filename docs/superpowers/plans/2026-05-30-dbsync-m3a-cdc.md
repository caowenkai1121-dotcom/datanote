# M3a CDC 核心增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 给 CDC 实时同步加心跳防 offset 过期、扩展 JMX 监控指标、解析失败死信表、重置/重快照入口,提升生产可运维性。

**Architecture:** `CdcSyncEngine.buildProps` 加 `heartbeat.interval.ms`(构造参数,来自 CdcEngineManager @Value)。`CdcSyncEngine` 加读 snapshot/streaming JMX MBean 的方法,并入 `CdcEngineManager.metrics()`。新增 `dn_cdc_dead_letter` 表+实体+Mapper,`handleBatch` 解析失败时落死信(原仅 errorCount++ 静默跳过)。`CdcEngineManager.resetAndRestart` 停引擎→清 offset+schema_history→可选重启(snapshot.mode=initial 触发重新全量快照),`CdcController` 加 reset 端点(需 confirm)。**不碰增量快照 signal/DDL 同步(L,留后续)。**

**Tech Stack:** Java8、JUnit5、Debezium 1.9.7、JDK ManagementFactory(JMX)、MyBatis-Plus。零新依赖。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sql/28_cdc_dead_letter.sql` | dn_cdc_dead_letter 表 | Create |
| `model/DnCdcDeadLetter.java` | 实体 | Create |
| `mapper/DnCdcDeadLetterMapper.java` | Mapper | Create |
| `sync/engine/cdc/CdcSyncEngine.java` | 心跳 props + JMX 指标 + 死信落库 | Modify |
| `sync/service/CdcEngineManager.java` | 心跳配置注入 + metrics 扩展 + resetAndRestart | Modify |
| `sync/controller/CdcController.java` | reset 端点 | Modify |
| `src/main/resources/application.yml` | 心跳/死信开关配置 | Modify |
| `src/main/resources/static/workspace.html` | CDC 指标面板扩展 + 重置按钮 | Modify |

---

## Task 1: 死信表 + 实体 + Mapper

**Files:** Create `sql/28_cdc_dead_letter.sql`、`model/DnCdcDeadLetter.java`、`mapper/DnCdcDeadLetterMapper.java`

- [ ] **Step 1: DDL**

```sql
USE datanote;
CREATE TABLE IF NOT EXISTS dn_cdc_dead_letter (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    job_id        BIGINT       NOT NULL,
    source_db     VARCHAR(100) DEFAULT NULL,
    source_table  VARCHAR(128) DEFAULT NULL,
    origin_value  LONGTEXT     COMMENT 'JSON 原始变更(截断)',
    error_reason  TEXT         COMMENT '失败原因',
    error_type    VARCHAR(20)  COMMENT 'PARSE/APPLY',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_time (job_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC 死信(坏事件)';
```

- [ ] **Step 2: 实体**(参考 model/DnCdcOffset.java)

```java
package com.datanote.model;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("dn_cdc_dead_letter")
public class DnCdcDeadLetter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private String sourceDb;
    private String sourceTable;
    private String originValue;
    private String errorReason;
    private String errorType;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Mapper**

```java
package com.datanote.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnCdcDeadLetter;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface DnCdcDeadLetterMapper extends BaseMapper<DnCdcDeadLetter> {
}
```

- [ ] **Step 4:** `mvn -q compile`
- [ ] **Step 5: Commit** `feat(sync-m3a): CDC 死信表+实体+Mapper`

---

## Task 2: CdcSyncEngine 心跳 + JMX 指标扩展 + 死信落库

**Files:** Modify `sync/engine/cdc/CdcSyncEngine.java`

> **先读** 当前 CdcSyncEngine(已有 buildProps、getStreamingLagMs、handleBatch 解析失败 catch、构造器 11 参)。

- [ ] **Step 1: 构造器加 heartbeatIntervalMs + deadLetterMapper 两参**

构造器末尾加 `int heartbeatIntervalMs, com.datanote.mapper.DnCdcDeadLetterMapper deadLetterMapper`,存为 final 字段。

- [ ] **Step 2: buildProps 加心跳**(在背压配置后)

```java
        if (heartbeatIntervalMs > 0) {
            props.setProperty("heartbeat.interval.ms", String.valueOf(heartbeatIntervalMs));
        }
```

- [ ] **Step 3: JMX 指标扩展**(加方法,仿 getStreamingLagMs 的 MBean 读取)

```java
    /** 流式阶段累计事件数(读不到返回 null)。 */
    public Long getEventsSeen() { return readStreamingLong("TotalNumberOfEventsSeen"); }
    /** 快照是否完成(读不到返回 null)。 */
    public Boolean getSnapshotCompleted() { return readSnapshotBool("SnapshotCompleted"); }
    /** 快照是否进行中。 */
    public Boolean getSnapshotRunning() { return readSnapshotBool("SnapshotRunning"); }

    private Long readStreamingLong(String attr) {
        return readMbeanNumber("streaming", attr);
    }
    private Long readMbeanNumber(String context, String attr) {
        try {
            javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                "debezium.mysql:type=connector-metrics,context=" + context + ",server=datanote_cdc_" + job.getId());
            Object v = mbs.getAttribute(on, attr);
            return v == null ? null : ((Number) v).longValue();
        } catch (Exception e) { return null; }
    }
    private Boolean readSnapshotBool(String attr) {
        try {
            javax.management.MBeanServer mbs = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName on = new javax.management.ObjectName(
                "debezium.mysql:type=connector-metrics,context=snapshot,server=datanote_cdc_" + job.getId());
            Object v = mbs.getAttribute(on, attr);
            return v == null ? null : (Boolean) v;
        } catch (Exception e) { return null; }
    }
```
   （可把 getStreamingLagMs 内部也改为复用 readMbeanNumber("streaming","MilliSecondsBehindSource"),非必须）

- [ ] **Step 4: 死信落库**(handleBatch 解析失败 catch 块)

把原:
```java
            } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("CDC 解析失败 ...", e);
                continue;
            }
```
改为追加死信落库(失败不影响主流程):
```java
            } catch (Exception e) {
                errorCount.incrementAndGet();
                log.error("CDC 解析失败 jobId={} value={}", job.getId(), abbreviate(value), e);
                saveDeadLetter(null, null, value, e.getMessage(), "PARSE");
                continue;
            }
```
   加方法:
```java
    private void saveDeadLetter(String db, String table, String originValue, String reason, String type) {
        if (deadLetterMapper == null) return;
        try {
            com.datanote.model.DnCdcDeadLetter dl = new com.datanote.model.DnCdcDeadLetter();
            dl.setJobId(job.getId());
            dl.setSourceDb(db); dl.setSourceTable(table);
            dl.setOriginValue(abbreviate(originValue));
            dl.setErrorReason(reason); dl.setErrorType(type);
            deadLetterMapper.insert(dl);
        } catch (Exception ignore) { /* 死信落库失败不影响主流程 */ }
    }
```

- [ ] **Step 5:** `mvn -q -Dtest=CdcSyncEngine*Test test` 不回归(注意:现有 CdcSyncEngineConfigTest 的 newEngine 辅助会因构造器加 2 参编译失败,需补默认参 `30000, null`)+ `mvn -q compile`。
- [ ] **Step 6: Commit** `feat(sync-m3a): CDC 心跳+JMX指标扩展+解析失败死信`

---

## Task 3: CdcEngineManager 心跳配置 + metrics 扩展 + resetAndRestart

**Files:** Modify `sync/service/CdcEngineManager.java`、`application.yml`

- [ ] **Step 1: application.yml 加**(datanote.sync.cdc 下)

```yaml
      heartbeat-interval-ms: ${SYNC_CDC_HEARTBEAT_MS:30000}
```

- [ ] **Step 2: CdcEngineManager 注入心跳配置 + deadLetterMapper**
  - 加 `@Value("${datanote.sync.cdc.heartbeat-interval-ms:30000}") private int cdcHeartbeatMs;`
  - 构造器加参 `DnCdcDeadLetterMapper deadLetterMapper`(存 final 字段)。
  - `doStart` 里 `new CdcSyncEngine(...)` 末尾补两参 `cdcHeartbeatMs, deadLetterMapper`。

- [ ] **Step 3: metrics() 扩展**(在现有 readCount/writeCount/errorCount/lagMs 后)

```java
            m.put("eventsSeen", engine.getEventsSeen());
            m.put("snapshotRunning", engine.getSnapshotRunning());
            m.put("snapshotCompleted", engine.getSnapshotCompleted());
```

- [ ] **Step 4: resetAndRestart**(新方法)

```java
    /** 重置 CDC:停引擎→清 offset+schema_history→可选重启(重新全量快照)。需调用方确认。 */
    public synchronized void resetAndRestart(Long jobId, boolean restart) {
        CdcSyncEngine engine = engines.remove(jobId);
        if (engine != null) {
            engine.stop();
            finalizeExecution(jobId, "STOPPED", engine);
        }
        // 清位点与 schema 历史(按 jobId)
        offsetMapper.delete(new LambdaQueryWrapper<com.datanote.model.DnCdcOffset>()
                .eq(com.datanote.model.DnCdcOffset::getJobId, jobId));
        historyMapper.delete(new LambdaQueryWrapper<com.datanote.model.DnCdcSchemaHistory>()
                .eq(com.datanote.model.DnCdcSchemaHistory::getJobId, jobId));
        log.warn("CDC 已重置 offset+schema_history jobId={}", jobId);
        if (restart) {
            DnSyncJob job = syncJobMapper.selectById(jobId);
            if (job != null) doStart(job);
        } else {
            updateStatus(jobId, "STOPPED");
        }
    }
```
   > **先读** DnCdcOffset/DnCdcSchemaHistory 实体确认 jobId 字段名(应为 `jobId` 映射 `job_id`);若不同按实际调整 lambda。

- [ ] **Step 5:** `mvn -q -Dtest=CdcEngineManagerTest test` 不回归(构造器加参,测试若 new CdcEngineManager(...) 需补 null 参)+ `mvn -q compile`。
- [ ] **Step 6: Commit** `feat(sync-m3a): CdcEngineManager 心跳+指标扩展+重置重快照`

---

## Task 4: CdcController reset 端点

**Files:** Modify `sync/controller/CdcController.java`

- [ ] **Step 1: 加端点**(需 confirm 防误操作)

```java
    @Operation(summary = "重置 CDC(清位点+schema历史,可选重新全量快照)")
    @PostMapping("/{jobId}/reset")
    public R<String> reset(@PathVariable Long jobId,
                           @RequestParam(defaultValue = "false") boolean confirm,
                           @RequestParam(defaultValue = "true") boolean restart) {
        if (!confirm) {
            return R.fail("高危操作,需 confirm=true 确认");
        }
        try {
            cdcEngineManager.resetAndRestart(jobId, restart);
            return R.ok(restart ? "已重置并重新全量快照" : "已重置(未重启)");
        } catch (Exception e) {
            log.error("重置 CDC 失败 jobId={}", jobId, e);
            return R.fail("重置失败: " + e.getMessage());
        }
    }
```
   import `org.springframework.web.bind.annotation.RequestParam`。

- [ ] **Step 2:** `mvn -q compile`。可给 controller 测试加 1 条:confirm=false 返回 fail。
- [ ] **Step 3: Commit** `feat(sync-m3a): CdcController 重置端点(需confirm)`

---

## Task 5: 前端 CDC 指标面板 + 重置按钮

**Files:** Modify `src/main/resources/static/workspace.html`

> **先读** workspace.html 中 CDC 指标展示与启停按钮相关 JS(搜 `/api/cdc` 调用、metrics 渲染)。沿用现有风格。

- [ ] **Step 1:** 指标面板展示新增字段(eventsSeen/snapshotRunning/snapshotCompleted),lagMs 已有。
- [ ] **Step 2:** CDC 任务操作区加"重置"按钮:`confirm()` 二次确认后 POST `/api/cdc/{jobId}/reset?confirm=true&restart=true`,成功后刷新状态。
- [ ] **Step 3:** 手测页面无 JS 报错;`mvn -q -Dtest=WorkspaceDbSyncUiTest test` 通过(可加断言)。
- [ ] **Step 4: Commit** `feat(sync-m3a): 前端 CDC 指标扩展+重置按钮`

---

## Self-Review

- **Spec 覆盖**: 心跳(Task2/3)、JMX指标(Task2/3/5)、死信表(Task1/2)、重置重快照(Task3/4/5)。✅
- **类型一致**: CdcSyncEngine 构造器 +2 参(heartbeatIntervalMs,deadLetterMapper)、metrics map 键、resetAndRestart(Long,boolean) 全程一致。✅
- **回归保护**: heartbeatIntervalMs<=0 不设心跳;deadLetterMapper=null 跳过死信;reset 需 confirm;现有 CDC 启停/恢复/at-least-once 不变。⚠️ 构造器加参会破坏现有测试的 newEngine/new 调用,需同步补默认参(已在 Task2/3 step 标注)。
- **延后**: 增量快照 signal 表、DDL 同步(L)留后续轮。

---

## 部署(M3a 里程碑)

1. `mvn -o clean package` 全过。
2. paramiko 分块上传 jar + 跑 `sql/28_cdc_dead_letter.sql`。
3. 备份+换jar+restart+验证(is-active/curl 8099→302/Started/CDC续传/job状态/dead_letter 表存在)。**特别验证 CDC 任务 2 重启后仍能续跑且心跳生效**。auto-rollback。
