# M2a 健壮性与容错 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 给同步加生产安全阀——脏数据阈值容错(坏行不让整任务白跑)、瞬时错误自动重试+退避、读写限速保护源/目标库、CDC 背压配置。

**Architecture:** 新增纯逻辑类 `BackoffCalculator`/`ErrorClassifier`/`RateLimiter`(全可单测)。`SyncContext` 加 errorLimit 配置 + dirtyCount + rateLimiter。两引擎批写失败时**回退逐行重试**定位坏行,坏行计入 dirtyCount 并按阈值判定整表成败;每页写后 `rateLimiter.acquire`。`SyncJobExecutor` 在 doRun 失败且错误可重试时按退避重提(attempt++,新执行记录)。CDC 背压走 `CdcSyncEngine.buildProps` 配置项。**本里程碑不碰多主键/chunk(留 M2b)、不碰调度线程池(留 M4)。**

**Tech Stack:** Java 8、JUnit 5、零新依赖(令牌桶自研)。

**数据模型:** DDL `sql/26_sync_robustness.sql`(DnSyncJob 加列)+ DnSyncJob/DnTaskExecution/SyncContext 字段 + application.yml CDC 配置项。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `sql/26_sync_robustness.sql` | DnSyncJob 加 error_limit_*/retry_backoff_*/rate_limit_*；DnTaskExecution 加 attempt | Create |
| `model/DnSyncJob.java` | 对应字段 | Modify |
| `model/DnTaskExecution.java` | attempt | Modify |
| `sync/util/BackoffCalculator.java` | 退避延迟计算 | Create |
| `sync/util/ErrorClassifier.java` | 瞬时/永久错误判定 | Create |
| `sync/util/RateLimiter.java` | 令牌桶限速 | Create |
| `sync/dto/SyncContext.java` | errorLimitRows/Ratio、dirtyCount、rateLimiter | Modify |
| `sync/engine/BatchWriter.java` | 批写+坏行回退逐行+限速(抽出复用) | Create |
| `sync/engine/FullSyncEngine.java` | 用 BatchWriter | Modify |
| `sync/engine/IncrementalSyncEngine.java` | 用 BatchWriter | Modify |
| `sync/service/SyncJobExecutor.java` | 退避重试 + 注入 errorLimit/rateLimiter | Modify |
| `sync/engine/cdc/CdcSyncEngine.java` | buildProps 加背压配置 | Modify |
| `src/main/resources/application.yml` | CDC 背压默认值 | Modify |
| `src/main/resources/static/workspace.html` | 录入 errorLimit/retry/rateLimit | Modify |

**统一契约:**
- `nullHandling` 既有。新增 `SyncContext.errorLimitRows`(Integer,null=不限)、`errorLimitRatio`(Double,null=不限)、`dirtyCount`(AtomicLong)、`rateLimiter`(RateLimiter,可 null=不限速)。
- `BatchWriter.flush(...)`:执行当前缓冲批;成功→commit+writeCount+=n;失败→rollback+逐行重试,坏行 dirtyCount++/日志,超阈值抛 `DirtyDataExceededException`。
- `ErrorClassifier.isTransient(Throwable)`:连接/死锁/锁等待→true;其余→false。
- `BackoffCalculator.delaySeconds(attempt, type, baseSec, maxSec)`:FIXED_DELAY→baseSec;EXPONENTIAL→min(maxSec, baseSec*2^(attempt-1))。
- `RateLimiter.reserve(n, nowNanos)`:返回需等待纳秒(0=立即),纯函数可测;`acquire(n)` 生产用。

---

## Task 1: DDL + 实体字段

**Files:** Create `sql/26_sync_robustness.sql`；Modify `model/DnSyncJob.java`、`model/DnTaskExecution.java`

- [ ] **Step 1: DDL(MySQL8 幂等,用 information_schema 判断,参考 25_sync_pipeline.sql 的 PREPARE 模式)**

为每列写一段 `SET @c:=(SELECT COUNT(*) ... COLUMN_NAME='x'); SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN ...','SELECT 1'); PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;`,列:
- `dn_sync_job.error_limit_rows INT NULL COMMENT '脏数据条数上限,null不限'`
- `dn_sync_job.error_limit_ratio DECIMAL(5,4) NULL COMMENT '脏数据比率上限'`
- `dn_sync_job.retry_backoff_type VARCHAR(20) DEFAULT 'FIXED_DELAY'`
- `dn_sync_job.retry_backoff_delay INT DEFAULT 5 COMMENT '退避基数秒'`
- `dn_sync_job.rate_limit_mode VARCHAR(10) DEFAULT 'NONE' COMMENT 'NONE/ROWS/BATCHES'`
- `dn_sync_job.rate_limit_value INT NULL COMMENT '令牌/秒'`
- `dn_task_execution.attempt INT DEFAULT 1 COMMENT '第几次重试'`(注意 TABLE_NAME='dn_task_execution')

- [ ] **Step 2: DnSyncJob 加字段**(@Data,放 M1 字段后)

```java
    // M2a 健壮性
    private Integer errorLimitRows;
    private java.math.BigDecimal errorLimitRatio;
    private String retryBackoffType;
    private Integer retryBackoffDelay;
    private String rateLimitMode;
    private Integer rateLimitValue;
```

- [ ] **Step 3: DnTaskExecution 加 attempt**

```java
    private Integer attempt;
```
(先读 DnTaskExecution.java 确认 @Data 与现有字段,就近追加)

- [ ] **Step 4:** `mvn -q compile` 通过。

- [ ] **Step 5: Commit** `feat(sync-m2a): DDL+实体 错误阈值/重试退避/限速字段`

---

## Task 2: BackoffCalculator(纯逻辑 TDD)

**Files:** Create `sync/util/BackoffCalculator.java` + test

- [ ] **Step 1: 失败测试**(JUnit5)

```java
package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class BackoffCalculatorTest {
    @Test void fixedDelay() {
        assertEquals(5, BackoffCalculator.delaySeconds(1,"FIXED_DELAY",5,300));
        assertEquals(5, BackoffCalculator.delaySeconds(3,"FIXED_DELAY",5,300));
    }
    @Test void exponential() {
        assertEquals(5, BackoffCalculator.delaySeconds(1,"EXPONENTIAL",5,300));
        assertEquals(10, BackoffCalculator.delaySeconds(2,"EXPONENTIAL",5,300));
        assertEquals(20, BackoffCalculator.delaySeconds(3,"EXPONENTIAL",5,300));
    }
    @Test void exponentialCapped() {
        assertEquals(300, BackoffCalculator.delaySeconds(10,"EXPONENTIAL",5,300));
    }
    @Test void nullTypeDefaultsFixed() {
        assertEquals(5, BackoffCalculator.delaySeconds(2,null,5,300));
    }
}
```

- [ ] **Step 2: 确认失败** `mvn -q -Dtest=BackoffCalculatorTest test`

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;
/** 重试退避延迟计算。 */
public final class BackoffCalculator {
    private BackoffCalculator() {}
    public static int delaySeconds(int attempt, String type, int baseSec, int maxSec) {
        if (attempt < 1) attempt = 1;
        if ("EXPONENTIAL".equalsIgnoreCase(type)) {
            long d = (long) baseSec << (attempt - 1); // baseSec * 2^(attempt-1)
            return (int) Math.min(maxSec, d);
        }
        return Math.min(maxSec, baseSec);
    }
}
```

- [ ] **Step 4: 确认通过** `mvn -q -Dtest=BackoffCalculatorTest test`
- [ ] **Step 5: Commit** `feat(sync-m2a): BackoffCalculator 退避延迟`

---

## Task 3: ErrorClassifier(纯逻辑 TDD)

**Files:** Create `sync/util/ErrorClassifier.java` + test

- [ ] **Step 1: 失败测试**

```java
package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
public class ErrorClassifierTest {
    @Test void connectionStateTransient() {
        assertTrue(ErrorClassifier.isTransient(new SQLException("x","08S01")));
    }
    @Test void deadlockTransient() {
        assertTrue(ErrorClassifier.isTransient(new SQLException("deadlock","40001",1213)));
    }
    @Test void lockWaitTransient() {
        assertTrue(ErrorClassifier.isTransient(new SQLException("lock","HY000",1205)));
    }
    @Test void syntaxErrorPermanent() {
        assertFalse(ErrorClassifier.isTransient(new SQLException("bad sql","42000",1064)));
    }
    @Test void nestedCauseScanned() {
        assertTrue(ErrorClassifier.isTransient(new RuntimeException(new SQLException("x","08S01"))));
    }
    @Test void nullFalse() { assertFalse(ErrorClassifier.isTransient(null)); }
}
```

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** 区分瞬时错误(可重试)与永久错误。扫异常链。 */
public final class ErrorClassifier {
    private static final Set<Integer> TRANSIENT_CODES = new HashSet<>(Arrays.asList(1205, 1213)); // 锁等待超时/死锁
    private ErrorClassifier() {}
    public static boolean isTransient(Throwable t) {
        int guard = 0;
        while (t != null && guard++ < 20) {
            if (t instanceof SQLException) {
                SQLException se = (SQLException) t;
                String state = se.getSQLState();
                if (state != null && (state.startsWith("08") || "40001".equals(state))) return true;
                if (TRANSIENT_CODES.contains(se.getErrorCode())) return true;
                String m = se.getMessage();
                if (m != null && m.toLowerCase().contains("communications link failure")) return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
```

- [ ] **Step 4: 确认通过**
- [ ] **Step 5: Commit** `feat(sync-m2a): ErrorClassifier 瞬时/永久错误判定`

---

## Task 4: RateLimiter(令牌桶 纯逻辑 TDD)

**Files:** Create `sync/util/RateLimiter.java` + test

- [ ] **Step 1: 失败测试**(测纯函数 reserve,注入 nowNanos 避免时间依赖)

```java
package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class RateLimiterTest {
    @Test void tokensAvailableNoWait() {
        RateLimiter r = new RateLimiter(100, 0L); // 100/s, 起始满桶
        assertEquals(0L, r.reserve(50, 0L));      // 桶有100,取50不等
    }
    @Test void deficitWaits() {
        RateLimiter r = new RateLimiter(100, 0L);
        r.reserve(100, 0L);                        // 取空
        long w = r.reserve(100, 0L);               // 再取100,需等 ~1s
        assertTrue(w > 0);
        assertEquals(1_000_000_000L, w, 2_000_000L); // ≈1s
    }
    @Test void refillOverTime() {
        RateLimiter r = new RateLimiter(100, 0L);
        r.reserve(100, 0L);                        // 取空
        assertEquals(0L, r.reserve(50, 500_000_000L)); // 0.5s 后补 50,够取 50
    }
}
```

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**

```java
package com.datanote.sync.util;
/** 简单令牌桶限速。reserve 为纯函数(注入 now),acquire 生产用(System.nanoTime+sleep)。 */
public final class RateLimiter {
    private final double ratePerSec;
    private double tokens;
    private long lastNanos;
    public RateLimiter(double ratePerSec, long startNanos) {
        this.ratePerSec = ratePerSec <= 0 ? 1 : ratePerSec;
        this.tokens = this.ratePerSec; // 起始满桶(容量=1秒额度)
        this.lastNanos = startNanos;
    }
    /** 预约 n 个令牌,返回需等待的纳秒(0=立即可用)。已按"等待后消费"推进内部状态。 */
    public synchronized long reserve(int n, long nowNanos) {
        double elapsedSec = Math.max(0, (nowNanos - lastNanos) / 1_000_000_000.0);
        tokens = Math.min(ratePerSec, tokens + elapsedSec * ratePerSec);
        lastNanos = nowNanos;
        if (tokens >= n) { tokens -= n; return 0L; }
        double deficit = n - tokens;
        long waitNanos = (long) (deficit / ratePerSec * 1_000_000_000.0);
        tokens = 0;
        lastNanos = nowNanos + waitNanos;
        return waitNanos;
    }
    /** 生产用:阻塞直到可消费 n 个令牌。 */
    public void acquire(int n) {
        long w = reserve(n, System.nanoTime());
        if (w > 0) {
            try { Thread.sleep(w / 1_000_000L, (int) (w % 1_000_000L)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
```

- [ ] **Step 4: 确认通过**
- [ ] **Step 5: Commit** `feat(sync-m2a): RateLimiter 令牌桶限速`

---

## Task 5: SyncContext 加 errorLimit/dirtyCount/rateLimiter + 异常类型

**Files:** Modify `sync/dto/SyncContext.java`；Create `sync/engine/DirtyDataExceededException.java`

- [ ] **Step 1: SyncContext 加字段**(放 errorCount 附近)

```java
    /** M2a：脏数据阈值(null=不限)。 */
    private Integer errorLimitRows;
    private Double errorLimitRatio;
    /** M2a：坏行累计。 */
    private final java.util.concurrent.atomic.AtomicLong dirtyCount = new java.util.concurrent.atomic.AtomicLong(0);
    /** M2a：限速器(null=不限速)。 */
    private com.datanote.sync.util.RateLimiter rateLimiter;
```

- [ ] **Step 2: 新建异常**

```java
package com.datanote.sync.engine;
/** 脏数据超阈值,中止该表同步。 */
public class DirtyDataExceededException extends RuntimeException {
    public DirtyDataExceededException(String msg) { super(msg); }
}
```

- [ ] **Step 3:** `mvn -q compile`
- [ ] **Step 4: Commit** `feat(sync-m2a): SyncContext 加 errorLimit/dirtyCount/rateLimiter`

---

## Task 6: BatchWriter(批写+坏行回退逐行+限速 TDD)

**Files:** Create `sync/engine/BatchWriter.java` + test

**契约:** `BatchWriter` 持有 writePs、tgtConn、ctx。`add(Object[] writeRow)` 缓冲一行(setObject+addBatch+缓存值);`flush()` 执行批:成功→commit+writeCount+=n+清缓冲;失败→rollback+对缓冲每行单独执行(新 Statement 用 writePs 重绑),坏行 dirtyCount++/日志/检查阈值(dirtyCount>errorLimitRows 或 dirtyCount/(readCount)>errorLimitRatio → 抛 DirtyDataExceededException),好行 commit。坏行定位用逐行 executeUpdate。**单测聚焦阈值判定纯逻辑**,抽 `static boolean exceeded(long dirty,long read,Integer limitRows,Double limitRatio)`。

- [ ] **Step 1: 失败测试**(测阈值纯函数)

```java
package com.datanote.sync.engine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class BatchWriterThresholdTest {
    @Test void noLimitNeverExceeds() {
        assertFalse(BatchWriter.exceeded(1000, 1000, null, null));
    }
    @Test void rowsLimit() {
        assertFalse(BatchWriter.exceeded(5, 100, 10, null));
        assertTrue(BatchWriter.exceeded(11, 100, 10, null));
    }
    @Test void ratioLimit() {
        assertFalse(BatchWriter.exceeded(5, 100, null, 0.1));
        assertTrue(BatchWriter.exceeded(11, 100, null, 0.1));
    }
    @Test void eitherTriggers() {
        assertTrue(BatchWriter.exceeded(11, 100, 10, 0.5)); // 条数先触发
    }
}
```

- [ ] **Step 2: 确认失败**

- [ ] **Step 3: 实现**(阈值纯函数 + 批写逐行回退;以下为骨架,实现者补全 add/flush 的 JDBC 细节,确保 writePs 复用与 clearBatch)

```java
package com.datanote.sync.engine;
import com.datanote.sync.dto.SyncContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/** 批量写:正常走 addBatch/executeBatch;批失败回退逐行定位坏行,按 errorLimit 阈值容错。 */
public final class BatchWriter {
    private final PreparedStatement ps;
    private final Connection conn;
    private final SyncContext ctx;
    private final int writeColCount;          // 写列数(含 markTs 列)
    private final List<Object[]> buffer = new ArrayList<>();

    public BatchWriter(PreparedStatement ps, Connection conn, SyncContext ctx, int writeColCount) {
        this.ps = ps; this.conn = conn; this.ctx = ctx; this.writeColCount = writeColCount;
    }

    /** 缓冲一行(writeRow 长度==writeColCount,已含 markTs 时间戳)。 */
    public void add(Object[] writeRow) throws Exception {
        for (int i = 0; i < writeColCount; i++) ps.setObject(i + 1, writeRow[i]);
        ps.addBatch();
        buffer.add(writeRow);
    }

    public int buffered() { return buffer.size(); }

    /** 执行缓冲批。成功 commit 并累加 writeCount;失败回退逐行,坏行容错或超阈值抛 DirtyDataExceededException。 */
    public void flush() throws Exception {
        if (buffer.isEmpty()) return;
        try {
            ps.executeBatch();
            conn.commit();
            ctx.getWriteCount().addAndGet(buffer.size());
        } catch (Exception batchEx) {
            conn.rollback();
            ps.clearBatch();
            ctx.log("WARN", "批写失败,回退逐行定位坏行: " + batchEx.getMessage());
            int ok = 0;
            for (Object[] row : buffer) {
                try {
                    for (int i = 0; i < writeColCount; i++) ps.setObject(i + 1, row[i]);
                    ps.executeUpdate();
                    ok++;
                } catch (Exception rowEx) {
                    long dirty = ctx.getDirtyCount().incrementAndGet();
                    ctx.log("ERROR", "坏行丢弃(累计脏数据=" + dirty + "): " + rowEx.getMessage());
                    if (exceeded(dirty, ctx.getReadCount().get(), ctx.getErrorLimitRows(), ctx.getErrorLimitRatio())) {
                        conn.commit(); // 保住已成功的好行
                        ctx.getWriteCount().addAndGet(ok);
                        throw new DirtyDataExceededException("脏数据超阈值,累计=" + dirty);
                    }
                }
            }
            conn.commit();
            ctx.getWriteCount().addAndGet(ok);
        } finally {
            ps.clearBatch();
            buffer.clear();
        }
    }

    /** 脏数据是否超阈值(条数 OR 比率,任一触发;均 null=不限)。 */
    public static boolean exceeded(long dirty, long read, Integer limitRows, Double limitRatio) {
        if (limitRows != null && dirty > limitRows) return true;
        if (limitRatio != null && read > 0 && (double) dirty / read > limitRatio) return true;
        return false;
    }
}
```

- [ ] **Step 4: 确认通过** `mvn -q -Dtest=BatchWriterThresholdTest test`
- [ ] **Step 5: Commit** `feat(sync-m2a): BatchWriter 批写+坏行回退逐行+阈值容错`

---

## Task 7: 两引擎改用 BatchWriter + 限速

**Files:** Modify `sync/engine/FullSyncEngine.java`、`IncrementalSyncEngine.java`

> **先读** 两引擎当前(M1 已改的)读取循环。把"手工 addBatch/executeBatch/commit/clearBatch + writeCount"替换为 BatchWriter。markTs 时间戳在构造 writeRow 时拼到末列。每页 flush 后调 `if (ctx.getRateLimiter()!=null) ctx.getRateLimiter().acquire(rowsWritten)`。

- [ ] **Step 1: FullSyncEngine 改造**
  - writePs 创建后:`BatchWriter bw = new BatchWriter(writePs, tgtConn, ctx, writeColumns.size());`
  - 行循环:processed!=null 时构造 `Object[] writeRow`(长度 writeColumns.size();前 srcColumns.size() 列填 row[i],若 markTs 末列填 `new Timestamp(now)`),`bw.add(writeRow); rowsWritten++;`
  - 原 `if(rowsWritten>0){executeBatch;commit;...}` 整块替换为页末 `bw.flush();`(BatchWriter 内部已累加 writeCount)。flush 后:`if (ctx.getRateLimiter()!=null && rowsWritten>0) ctx.getRateLimiter().acquire(rowsWritten);`
  - `readCount += rowsThisPage`、游标推进、末页 break 不变。
- [ ] **Step 2: IncrementalSyncEngine 同样改造**(注意 maxValue/游标推进逻辑不变,只换写入部分)。
- [ ] **Step 3:** `mvn -q -Dtest=*Sync*,*Engine* test` 不回归 + `mvn -q compile`。
- [ ] **Step 4: Commit** `feat(sync-m2a): 两引擎改用 BatchWriter + 限速 acquire`

---

## Task 8: SyncJobExecutor 注入 errorLimit/rateLimiter + 退避重试

**Files:** Modify `sync/service/SyncJobExecutor.java`

- [ ] **Step 1: doRun 构建 ctx 处注入**(setGlobalPostSql 后)

```java
        ctx.setErrorLimitRows(job.getErrorLimitRows());
        ctx.setErrorLimitRatio(job.getErrorLimitRatio() == null ? null : job.getErrorLimitRatio().doubleValue());
        String rlMode = job.getRateLimitMode();
        if (("ROWS".equalsIgnoreCase(rlMode) || "BATCHES".equalsIgnoreCase(rlMode))
                && job.getRateLimitValue() != null && job.getRateLimitValue() > 0) {
            ctx.setRateLimiter(new com.datanote.sync.util.RateLimiter(job.getRateLimitValue(), System.nanoTime()));
        }
```

- [ ] **Step 2: 退避重试**(包装 doRun 的失败重提)。`run()` 提交线程池的 lambda 改为调 `doRunWithRetry(job, triggerType, exec)`。新增方法:

```java
    private void doRunWithRetry(DnSyncJob job, String triggerType, DnTaskExecution firstExec) {
        int maxRetries = job.getRetryTimes() == null ? 0 : Math.max(0, job.getRetryTimes());
        String btype = job.getRetryBackoffType();
        int base = job.getRetryBackoffDelay() == null ? 5 : job.getRetryBackoffDelay();
        int attempt = 1;
        DnTaskExecution exec = firstExec;
        while (true) {
            FailureInfo fi = doRun(job, triggerType, exec, attempt);
            if (fi == null || !fi.retryable || attempt > maxRetries) return; // 成功/不可重试/重试用尽
            int delay = com.datanote.sync.util.BackoffCalculator.delaySeconds(attempt, btype, base, 300);
            try { Thread.sleep(delay * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            attempt++;
            exec = newExecution(job.getId(), triggerType, attempt); // 新执行记录,attempt 递增
        }
    }
```

> 需把 `doRun` 改为返回 `FailureInfo`(含 `boolean retryable`):捕获到异常时用 `ErrorClassifier.isTransient` 判定 retryable;`DirtyDataExceededException` 视为**不可重试**(配置/数据问题);成功/手动停止 return null。新增 `newExecution(jobId,triggerType,attempt)` 复用 run() 里建 RUNNING 执行记录的逻辑并 set attempt。`run()` 首条执行记录 attempt=1。
> **保持现有行为**:无重试配置(retryTimes=0/null)时 maxRetries=0,首次失败即返回,与现状一致。

- [ ] **Step 3:** `mvn -q test` 全量不回归(注意现有 SyncJobExecutor 相关测试)。
- [ ] **Step 4: Commit** `feat(sync-m2a): SyncJobExecutor 退避重试+注入errorLimit/限速`

---

## Task 9: CDC 背压配置

**Files:** Modify `sync/engine/cdc/CdcSyncEngine.java`、`application.yml`

> **先读** CdcSyncEngine.buildProps()(M1/B 已存在),按现有 props.setProperty 风格追加。从 application.yml 读默认值(可用 @Value 或现有配置注入方式——先看 CdcSyncEngine 怎么拿配置的)。

- [ ] **Step 1: application.yml 加**(datanote.sync.cdc 节点下)

```yaml
datanote:
  sync:
    cdc:
      max-batch-size: ${SYNC_CDC_MAX_BATCH:2048}
      max-queue-size: ${SYNC_CDC_MAX_QUEUE:8192}
      poll-interval-ms: ${SYNC_CDC_POLL_MS:500}
```

- [ ] **Step 2: buildProps 追加**

```java
        props.setProperty("max.batch.size", String.valueOf(cdcMaxBatchSize));
        props.setProperty("max.queue.size", String.valueOf(cdcMaxQueueSize));
        props.setProperty("poll.interval.ms", String.valueOf(cdcPollIntervalMs));
```
(字段来源按 CdcSyncEngine 现有配置注入方式补;若它不持有 Spring 配置,则在 CdcEngineManager 创建引擎时透传)

- [ ] **Step 3:** `mvn -q -Dtest=CdcSyncEngine*Test test` 不回归。
- [ ] **Step 4: Commit** `feat(sync-m2a): CDC 背压配置(max.batch/queue/poll)`

---

## Task 10: 前端录入

**Files:** Modify `src/main/resources/static/workspace.html`

> 沿用 M1 前端模式(dbsyncFillAdvanced/ResetAdvanced/dbsyncSaveJob)。在高级选项区加:脏数据条数上限 input、脏数据比率 input、重试次数 input(绑 retryTimes)、退避类型 select(FIXED_DELAY/EXPONENTIAL)、退避基数秒 input、限速模式 select(NONE/ROWS/BATCHES)、限速值 input。保存写入 payload 对应字段;回填 job 对应字段;重置清空。可给 WorkspaceDbSyncUiTest 加断言新控件 id 存在。

- [ ] **Step 1:** 加 7 个控件 + 保存/回填/重置三处闭环。
- [ ] **Step 2:** `mvn -q -Dtest=WorkspaceDbSyncUiTest test` 通过。
- [ ] **Step 3: Commit** `feat(sync-m2a): 前端录入 errorLimit/retry/限速`

---

## Self-Review

- **Spec 覆盖**: 脏数据阈值(Task5/6/7)、重试退避(Task2/3/8)、限速(Task4/5/7/8)、CDC背压(Task9)、数据模型(Task1)、前端(Task10)。✅
- **类型一致**: `BatchWriter.exceeded(long,long,Integer,Double)`、`BackoffCalculator.delaySeconds(int,String,int,int)`、`ErrorClassifier.isTransient(Throwable)`、`RateLimiter.reserve(int,long)/acquire(int)`、`SyncContext.errorLimitRows/Ratio/dirtyCount/rateLimiter` —— 全程一致。✅
- **依赖顺序**: 1(模型)→2,3,4(纯逻辑)→5(ctx+异常)→6(BatchWriter)→7(引擎)→8(executor)→9(CDC)→10(前端)。✅
- **回归保护**: 无 errorLimit/retry/rateLimit 配置时行为同现状(maxRetries=0、rateLimiter=null、阈值 null→exceeded 恒 false、BatchWriter 批成功路径等价原逻辑)。✅

---

## 部署(M2a 里程碑)

1. `mvn -o clean package`(209+ 新增测试全过)。
2. paramiko 分块上传 jar(参考 M1 部署脚本 dn_upload.py/dn_swap.py)。
3. 跑 `sql/26_sync_robustness.sql`。
4. 备份+换 jar+`systemctl restart datanote`+验证(is-active/curl 8099→302/Started 日志/CDC 续传/job 状态)。auto-rollback on failure。
