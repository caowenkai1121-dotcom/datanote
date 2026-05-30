# M3b 可运维(审计/告警/预检增强) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 加执行审计(配置/操作留痕)、失败告警外发(Webhook/钉钉,JDK 零依赖)、预检增强(CDC binlog/权限 + 修正多主键提示),提升生产可运维性。

**Architecture:** 新增 `dn_sync_job_audit` 表+实体+Mapper+`AuditLogService`,在 save/run/stop/CDC重置 处记审计。新增 `AlertService`(JDK HttpURLConnection 异步发送 + 同 job 限流),`SyncJobExecutor` 失败终态与 `CdcEngineManager` CDC 失败时触发。增强既有 `SyncJobService.precheck`:修多主键提示、CDC 模式查源库 binlog/账号权限、目标可写。**监控大盘与断点管理UI 留 M3b-2。**

**Tech Stack:** Java8、JUnit5、JDK `HttpURLConnection`/`MessageDigest` 无、MyBatis-Plus。零新依赖。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sql/29_sync_audit.sql` | dn_sync_job_audit 表 | Create |
| `model/DnSyncJobAudit.java` / `mapper/DnSyncJobAuditMapper.java` | 实体/Mapper | Create |
| `sync/service/AuditLogService.java` | 审计记录 | Create |
| `sync/service/AlertService.java` | 告警外发(异步+限流) | Create |
| `sync/service/SyncJobService.java` | save 记审计 + precheck 增强 | Modify |
| `sync/service/SyncJobExecutor.java` | run/stop 审计 + 失败告警 | Modify |
| `sync/service/CdcEngineManager.java` | CDC 失败告警 | Modify |
| `sync/controller/SyncJobController.java` | 审计历史端点 | Modify |
| `application.yml` | 告警配置 | Modify |
| `src/main/resources/static/workspace.html` | 审计历史展示 | Modify |

**契约:**
- `AuditLogService.record(Long jobId, String jobName, String op, String detail)`:op ∈ CREATE/UPDATE/RUN/STOP/RESET/DELETE。
- `AlertService.alert(Long jobId, String jobName, String type, String message)`:异步发送到配置的 webhook/钉钉;同 jobId+type 在 throttle 窗口内只发一次;`datanote.alert.enabled=false` 时全禁。
- precheck 返回结构不变(`{ok, checks:[{name,ok,message}]}`),只增/改 checks。

---

## Task 1: 审计表+实体+Mapper

**Files:** Create `sql/29_sync_audit.sql`、`model/DnSyncJobAudit.java`、`mapper/DnSyncJobAuditMapper.java`

- [ ] **Step 1: DDL**

```sql
USE datanote;
CREATE TABLE IF NOT EXISTS dn_sync_job_audit (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    job_id         BIGINT       NOT NULL,
    job_name       VARCHAR(200) DEFAULT NULL,
    operation_type VARCHAR(20)  NOT NULL COMMENT 'CREATE/UPDATE/RUN/STOP/RESET/DELETE',
    operator       VARCHAR(64)  DEFAULT NULL,
    change_detail  LONGTEXT     COMMENT '变更/操作详情',
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_job_created (job_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务操作审计';
```

- [ ] **Step 2: 实体**(`@Data @TableName("dn_sync_job_audit")`,id@TableId AUTO,字段 jobId/jobName/operationType/operator/changeDetail/createdAt)。
- [ ] **Step 3: Mapper**(extends BaseMapper,@Mapper)。
- [ ] **Step 4:** `mvn -q compile`。
- [ ] **Step 5: Commit** `feat(sync-m3b): 审计表+实体+Mapper`

---

## Task 2: AuditLogService + 接入

**Files:** Create `sync/service/AuditLogService.java`；Modify `SyncJobService.java`、`SyncJobExecutor.java`、`CdcEngineManager.java`、`SyncJobController.java`

- [ ] **Step 1: AuditLogService**

```java
package com.datanote.sync.service;
import com.datanote.mapper.DnSyncJobAuditMapper;
import com.datanote.model.DnSyncJobAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final DnSyncJobAuditMapper auditMapper;
    /** 记一条审计(失败不影响主流程)。 */
    public void record(Long jobId, String jobName, String op, String detail) {
        try {
            DnSyncJobAudit a = new DnSyncJobAudit();
            a.setJobId(jobId); a.setJobName(jobName);
            a.setOperationType(op);
            a.setChangeDetail(detail == null ? null : (detail.length() > 60000 ? detail.substring(0, 60000) : detail));
            auditMapper.insert(a);
        } catch (Exception e) { log.warn("审计记录失败 jobId={} op={}", jobId, op, e); }
    }
}
```

- [ ] **Step 2: SyncJobService.save 记审计**:注入 AuditLogService(@RequiredArgsConstructor 加 final 字段)。在 save 内,区分新建/更新(id 是否为空):新建后 record(id, name, "CREATE", 新配置摘要);更新前查旧值,record(id, name, "UPDATE", "旧→新关键字段变更")。摘要可简单 JSON.toJSONString 关键字段(syncMode/writeMode/tableConfig 等),勿过长。
- [ ] **Step 3: SyncJobExecutor**:run() 成功提交后 record(jobId, name, "RUN", "triggerType="+triggerType);(注入 AuditLogService)。
- [ ] **Step 4: CdcEngineManager.resetAndRestart** 记 record(jobId, ..., "RESET", "restart="+restart)(注入 AuditLogService)。
- [ ] **Step 5: SyncJobController 加端点**

```java
    @Operation(summary = "操作审计历史")
    @GetMapping("/{id}/audit")
    public R<List<com.datanote.model.DnSyncJobAudit>> audit(@PathVariable Long id) {
        return R.ok(auditMapper.selectList(new LambdaQueryWrapper<com.datanote.model.DnSyncJobAudit>()
            .eq(com.datanote.model.DnSyncJobAudit::getJobId, id)
            .orderByDesc(com.datanote.model.DnSyncJobAudit::getId).last("LIMIT 100")));
    }
```
   (注入 DnSyncJobAuditMapper)。

- [ ] **Step 6:** `mvn -q test` 不回归(构造器加参会破坏现有 SyncJobService/Executor/Manager 的 new 调用与测试,需同步补参)。注意 `SyncJobServiceValidateTest` 的 new SyncJobService(...) 又要加一参。
- [ ] **Step 7: Commit** `feat(sync-m3b): AuditLogService 记录 CRUD/RUN/STOP/RESET`

---

## Task 3: AlertService + 失败触发

**Files:** Create `sync/service/AlertService.java`；Modify `application.yml`、`SyncJobExecutor.java`、`CdcEngineManager.java`；Test `AlertServiceTest.java`

- [ ] **Step 1: application.yml 加**

```yaml
datanote:
  alert:
    enabled: ${ALERT_ENABLED:false}
    webhook-urls: ${ALERT_WEBHOOKS:}      # 逗号分隔通用 webhook
    dingtalk-webhook: ${ALERT_DINGTALK:}  # 钉钉机器人完整 webhook
    throttle-min: ${ALERT_THROTTLE_MIN:30}
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
```

- [ ] **Step 2: AlertService**(异步单线程 + 限流;纯逻辑 throttle 抽静态可测)

```java
package com.datanote.sync.service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
@Slf4j
@Service
public class AlertService {
    @Value("${datanote.alert.enabled:false}") private boolean enabled;
    @Value("${datanote.alert.webhook-urls:}") private String webhookUrls;
    @Value("${datanote.alert.dingtalk-webhook:}") private String dingtalk;
    @Value("${datanote.alert.throttle-min:30}") private int throttleMin;
    @Value("${datanote.alert.connect-timeout-ms:3000}") private int connectTimeout;
    @Value("${datanote.alert.read-timeout-ms:5000}") private int readTimeout;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"datanote-alert"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<String,Long> lastSent = new ConcurrentHashMap<>();
    public void alert(Long jobId, String jobName, String type, String message) {
        if (!enabled) return;
        String key = jobId + ":" + type;
        long now = System.currentTimeMillis();
        if (throttled(lastSent.get(key), now, throttleMin)) return;
        lastSent.put(key, now);
        String text = "[DataNote同步告警] 任务#" + jobId + " " + (jobName==null?"":jobName) + " | " + type + " | " + message;
        pool.execute(() -> send(text));
    }
    /** 纯逻辑:上次发送时间在 throttle 窗口内则限流(true=跳过)。 */
    public static boolean throttled(Long last, long now, int throttleMin) {
        return last != null && (now - last) < throttleMin * 60_000L;
    }
    private void send(String text) {
        if (dingtalk != null && !dingtalk.isEmpty()) {
            postJson(dingtalk, "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escape(text) + "\"}}");
        }
        if (webhookUrls != null && !webhookUrls.isEmpty()) {
            for (String u : webhookUrls.split(",")) {
                if (!u.trim().isEmpty()) postJson(u.trim(), "{\"text\":\"" + escape(text) + "\"}");
            }
        }
    }
    private static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private void postJson(String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(connectTimeout); conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            if (code >= 300) log.warn("告警发送返回 {} url={}", code, url);
            conn.disconnect();
        } catch (Exception e) { log.warn("告警发送失败 url={}: {}", url, e.getMessage()); }
    }
}
```

- [ ] **Step 3: 失败测试**(测纯逻辑 throttled)

```java
package com.datanote.sync.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class AlertServiceTest {
    @Test void firstSendNotThrottled() { assertFalse(AlertService.throttled(null, 1000L, 30)); }
    @Test void withinWindowThrottled() { assertTrue(AlertService.throttled(1000L, 1000L + 60_000L, 30)); }
    @Test void afterWindowNotThrottled() { assertFalse(AlertService.throttled(1000L, 1000L + 31L*60_000L, 30)); }
}
```
   (先写测试确认失败,再写实现)

- [ ] **Step 4: 触发**:
  - SyncJobExecutor 注入 AlertService;doRunWithRetry 在最终终态为 "FAILED" 时(重试用尽后)`alertService.alert(jobId, job.getJobName(), "FAILED", "同步失败");`(只在最终 FAILED 发,不在中途重试发)。
  - CdcEngineManager.flushAll 把引擎收尾为 FAILED 时 `alertService.alert(jobId, ..., "CDC_FAILED", "CDC引擎异常退出");`(注入 AlertService)。
- [ ] **Step 5:** `mvn -q -Dtest=AlertServiceTest test` + 全量 `mvn -q test` 不回归。
- [ ] **Step 6: Commit** `feat(sync-m3b): AlertService 失败告警外发(Webhook/钉钉,限流)`

---

## Task 4: precheck 增强

**Files:** Modify `sync/service/SyncJobService.java`

> **先读** 现有 precheck(行 208-267)。

- [ ] **Step 1: 修多主键提示**:把 table 检查里 `single = pk==1` 的判定改为支持多主键:`pk>=1` 视为 ok(M2b 已支持复合主键),消息:pk==1→"单列主键";pk>1→"复合主键(已支持)";pk==0→ok=false 但消息分情形("全量可流式INSERT;增量/CDC 需主键")。即:全量模式 pk==0 也算 ok(流式),增量/CDC 模式 pk==0 才 fail。按 job.getSyncMode() 区分。
- [ ] **Step 2: CDC 源库检查**(syncMode=CDC 时):用源连接查 `SHOW VARIABLES LIKE 'log_bin'`(应 ON)、`SHOW VARIABLES LIKE 'binlog_format'`(应 ROW)。查账号权限 `SHOW GRANTS`(含 REPLICATION SLAVE/CLIENT)。各加一条 check。查询失败记 check 失败不抛。
- [ ] **Step 3: 目标可写检查**:目标库已连通后,可选执行轻量探测(如能 listTables 即认为基本可用;不强写)。保留现有 target check 即可,消息补"(可连通)"。
- [ ] **Step 4:** `mvn -q -Dtest=*SyncJobService* test` + `mvn -q compile`。若有 precheck 相关测试断言旧消息,同步更新。
- [ ] **Step 5: Commit** `feat(sync-m3b): precheck 增强(多主键/CDC binlog权限/目标可写)`

---

## Task 5: 前端审计历史展示

**Files:** Modify `src/main/resources/static/workspace.html`

> **先读** 任务详情抽屉/执行历史展示。加"操作审计"tab 或区块:调 `GET /api/sync-job/{id}/audit`,列表展示 operationType/operator/changeDetail/createdAt。沿用现有执行历史渲染风格。

- [ ] **Step 1:** 加审计历史展示(详情抽屉内)。
- [ ] **Step 2:** `mvn -q -Dtest=WorkspaceDbSyncUiTest test`(可加断言)。
- [ ] **Step 3: Commit** `feat(sync-m3b): 前端审计历史展示`

---

## Self-Review

- **Spec 覆盖**: 执行审计(Task1/2/5)、告警外发(Task3)、预检增强(Task4)。✅(监控大盘/断点UI 明确留 M3b-2)
- **类型一致**: AuditLogService.record(Long,String,String,String)、AlertService.alert(Long,String,String,String)+throttled(Long,long,int) 全程一致。✅
- **回归保护**: alert.enabled 默认 false(不发);审计/告警失败均 try-catch 不影响主流程;多个 service 构造器加参需同步补现有 new 调用与测试。⚠️
- **延后**: 监控大盘、断点管理UI(M3b-2)。

---

## 部署(M3b 里程碑)

1. `mvn -o clean package`。
2. 分块上传 + 跑 `sql/29_sync_audit.sql`。
3. 备份+换jar+restart+验证(健康/CDC续传/job状态/audit 表存在)。alert 默认关,不影响。auto-rollback。
