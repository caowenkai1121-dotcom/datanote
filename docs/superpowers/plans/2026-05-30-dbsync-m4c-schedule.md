# M4c 任务依赖 DAG + 资源隔离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** ① 任务依赖 DAG:给同步任务配上游依赖,cron 到点且所有上游今日 SUCCESS 才触发(维表同步完再同步事实表)。② 资源隔离:SyncJobExecutor 固定 4 线程池升级为可配置优先级线程池,高优先级任务先跑。

**Architecture:** 新增 `dn_sync_job_dependency` 表+实体+Mapper + DnSyncJob 加 `priority`(DDL 31)。`SyncJobScheduler.tick` 在 shouldFire 后加依赖就绪门控(纯逻辑 `DepReadyChecker` 可单测)。`SyncJobExecutor` 的 `Executors.newFixedThreadPool(4)` 换为 `ThreadPoolExecutor` + `PriorityBlockingQueue`,任务包成 `PrioritizedTask`(按 job priority 排序,高优先先跑)。依赖增删端点 + 防环校验(DFS)。前端配依赖与优先级。

**Tech Stack:** Java8、JUnit5、JDK 并发。零新依赖。

---

## File Structure

| 文件 | 动作 |
|------|------|
| `sql/31_sync_dag.sql` | dn_sync_job_dependency 表 + dn_sync_job.priority — Create |
| `model/DnSyncJobDependency.java` / `mapper/DnSyncJobDependencyMapper.java` | 实体/Mapper — Create |
| `model/DnSyncJob.java` | priority — Modify |
| `sync/util/PrioritizedTask.java` | 优先级任务包装(Comparable) — Create |
| `sync/service/SyncJobExecutor.java` | 优先级线程池 — Modify |
| `sync/service/DepReadyChecker.java` | 依赖就绪判定(纯逻辑) — Create |
| `sync/service/SyncJobScheduler.java` | tick 依赖门控 — Modify |
| `sync/service/SyncJobService.java` | 依赖 CRUD + 防环 — Modify |
| `sync/controller/SyncJobController.java` | 依赖端点 — Modify |
| `application.yml` | 线程池配置 — Modify |
| `workspace.html` | 依赖配置 + 优先级 — Modify |

**契约:**
- `PrioritizedTask`:`implements Runnable, Comparable<PrioritizedTask>`;priority 高者 compareTo 在前(先执行)。
- `DepReadyChecker.allReady(List<Long> upstreamIds, Map<Long,String> upstreamLatestStatusToday)`:每个上游今日最新状态==SUCCESS 才 true;空依赖 true。
- `DnSyncJobDependency`:syncJobId(下游)/upstreamSyncJobId(上游)/dependsAll。

---

## Task 1: DDL + 实体 + Mapper

- [ ] **Step 1: `sql/31_sync_dag.sql`**

```sql
USE datanote;
CREATE TABLE IF NOT EXISTS dn_sync_job_dependency (
    id                   BIGINT NOT NULL AUTO_INCREMENT,
    sync_job_id          BIGINT NOT NULL COMMENT '下游任务',
    upstream_sync_job_id BIGINT NOT NULL COMMENT '上游任务',
    depends_all          TINYINT(1) DEFAULT 1 COMMENT '1=全部上游SUCCESS才触发',
    create_time          DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dep (sync_job_id, upstream_sync_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务依赖(轻量DAG)';
-- dn_sync_job.priority(幂等)
SET @c:=(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='datanote' AND TABLE_NAME='dn_sync_job' AND COLUMN_NAME='priority');
SET @s:=IF(@c=0,'ALTER TABLE dn_sync_job ADD COLUMN priority INT DEFAULT 5 COMMENT ''调度优先级,大者先跑''','SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
```

- [ ] **Step 2:** `DnSyncJobDependency` 实体(@Data @TableName,id@TableId AUTO,syncJobId/upstreamSyncJobId/dependsAll/createTime)+ `DnSyncJobDependencyMapper`(BaseMapper,@Mapper)。
- [ ] **Step 3:** DnSyncJob 加 `private Integer priority;`。
- [ ] **Step 4:** `mvn -q compile`。
- [ ] **Step 5: Commit** `feat(sync-m4c): DAG依赖表+priority实体`

---

## Task 2: 资源隔离(优先级线程池)

**Files:** Create `sync/util/PrioritizedTask.java` + test；Modify `SyncJobExecutor.java`、`application.yml`

- [ ] **Step 1: PrioritizedTask(TDD)**

测试 `PrioritizedTaskTest`(JUnit5):验证高 priority 排在前(用 PriorityQueue 取出顺序)。
```java
package com.datanote.sync.util;
import org.junit.jupiter.api.Test;
import java.util.PriorityQueue;
import static org.junit.jupiter.api.Assertions.*;
public class PrioritizedTaskTest {
    @Test void higherPriorityFirst() {
        PriorityQueue<PrioritizedTask> q = new PriorityQueue<>();
        q.add(new PrioritizedTask(1, 1L, () -> {}));
        q.add(new PrioritizedTask(9, 2L, () -> {}));
        q.add(new PrioritizedTask(5, 3L, () -> {}));
        assertEquals(9, q.poll().getPriority());
        assertEquals(5, q.poll().getPriority());
        assertEquals(1, q.poll().getPriority());
    }
    @Test void fifoWhenEqualPriority() {
        PriorityQueue<PrioritizedTask> q = new PriorityQueue<>();
        q.add(new PrioritizedTask(5, 100L, () -> {}));
        q.add(new PrioritizedTask(5, 101L, () -> {}));
        assertEquals(100L, q.poll().getJobId().longValue()); // seq 早者先(FIFO)
    }
}
```
实现:
```java
package com.datanote.sync.util;
import java.util.concurrent.atomic.AtomicLong;
/** 优先级任务包装:priority 大者先执行,同优先级按入队序 FIFO。 */
public final class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {
    private static final AtomicLong SEQ = new AtomicLong();
    private final int priority;
    private final Long jobId;
    private final Runnable delegate;
    private final long seq;
    public PrioritizedTask(int priority, Long jobId, Runnable delegate) {
        this.priority = priority; this.jobId = jobId; this.delegate = delegate; this.seq = SEQ.incrementAndGet();
    }
    public int getPriority() { return priority; }
    public Long getJobId() { return jobId; }
    @Override public void run() { delegate.run(); }
    @Override public int compareTo(PrioritizedTask o) {
        int c = Integer.compare(o.priority, this.priority); // 大优先级在前
        return c != 0 ? c : Long.compare(this.seq, o.seq);   // 同级 FIFO
    }
}
```
   注:`SEQ` 用 AtomicLong 静态计数(`Math.random`/`Date.now` 受限,但 AtomicLong 计数器 OK)。
commit: `feat(sync-m4c): PrioritizedTask 优先级任务包装`

- [ ] **Step 2: SyncJobExecutor 换池**

application.yml 加:
```yaml
datanote:
  sync:
    executor:
      pool-size: ${SYNC_EXECUTOR_POOL:4}
```
把 `private final ExecutorService pool = Executors.newFixedThreadPool(4);` 换为可配置优先级池。因 SyncJobExecutor 是 @Service @RequiredArgsConstructor,用 @Value + @PostConstruct 初始化(或字段直接构造):
```java
    @org.springframework.beans.factory.annotation.Value("${datanote.sync.executor.pool-size:4}")
    private int poolSize;
    private java.util.concurrent.ThreadPoolExecutor pool;
    @javax.annotation.PostConstruct
    public void initPool() {
        pool = new java.util.concurrent.ThreadPoolExecutor(poolSize, poolSize, 0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.PriorityBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "datanote-sync-exec"); t.setDaemon(true); return t; });
    }
```
   > 注意:现有 `recoverOrphans` 是 @PostConstruct;若已有 @PostConstruct 方法,把 initPool 逻辑并入或确保两个 @PostConstruct 都执行(Spring 支持多个 @PostConstruct?**否**,一个类只调一个。**合并**:在 recoverOrphans 开头先 initPool(),或新建一个 @PostConstruct 同时做两件事)。**先读 SyncJobExecutor 确认现有 @PostConstruct**,把池初始化并入。
   提交给 pool 的任务包成 PrioritizedTask(job.priority):run() 里 `pool.execute(new com.datanote.sync.util.PrioritizedTask(job.getPriority()==null?5:job.getPriority(), jobId, () -> { try { doRunWithRetry(...);} finally {runningJobs.remove; runningContexts.remove;} }));`。
   **超时 Future 处理**:原用 `Future<?> future = pool.submit(...)` 判 isDone。换池后 `pool.execute(PrioritizedTask)` 无 Future。超时改为:用 runningJobs/runningContexts 判断是否仍在跑(timeoutScheduler 到点查 runningContexts.get(jobId)!=null 则 requestStop),不依赖 Future.isDone。**先读现有超时逻辑**按此调整(去掉 future.isDone(),改查 runningContexts)。
   destroy() 的 pool.shutdown 保持。
commit: `feat(sync-m4c): SyncJobExecutor 优先级线程池(资源隔离)`

- [ ] **Step 3:** `mvn -q -Dtest=PrioritizedTaskTest test` + 全量 `mvn -q test`。
- [ ] **Step 4:** (已在 Step2 commit)

---

## Task 3: DAG 依赖门控

**Files:** Create `sync/service/DepReadyChecker.java` + test；Modify `SyncJobScheduler.java`、`SyncJobService.java`、`SyncJobController.java`

- [ ] **Step 1: DepReadyChecker(纯逻辑 TDD)**

```java
package com.datanote.sync.service;
import java.util.List;
import java.util.Map;
/** 依赖就绪判定:所有上游今日最新状态==SUCCESS 才就绪。 */
public final class DepReadyChecker {
    private DepReadyChecker() {}
    public static boolean allReady(List<Long> upstreamIds, Map<Long,String> latestStatus) {
        if (upstreamIds == null || upstreamIds.isEmpty()) return true;
        for (Long u : upstreamIds) {
            if (!"SUCCESS".equalsIgnoreCase(latestStatus.get(u))) return false;
        }
        return true;
    }
}
```
测试:空依赖 true;全 SUCCESS true;有非 SUCCESS/缺失 false。commit: `feat(sync-m4c): DepReadyChecker 依赖就绪判定`

- [ ] **Step 2: SyncJobScheduler.tick 门控**:注入 DnSyncJobDependencyMapper + DnTaskExecutionMapper。在 `shouldFire(...)` 为 true 后、submit 前:查该 job 的上游(dependencyMapper by syncJobId)→ 若非空,查每个上游今日(>= 当天 00:00)最新 DbSync 执行状态(taskExecutionMapper)→ DepReadyChecker.allReady。不就绪则 log "等待上游" 并 **不 put lastFireMap**(下个 tick 再判)、不 submit。
   > 关键:不就绪时不要更新 lastFireMap,否则错过本次 cron 触发点。但要防止同 tick 反复——可接受(30s 一次,上游就绪后下个 tick 触发)。

- [ ] **Step 3: SyncJobService 依赖 CRUD + 防环**:注入 DnSyncJobDependencyMapper。
  - `listDependencies(jobId)`:查 upstream 列表。
  - `addDependency(jobId, upstreamId)`:防自依赖(jobId!=upstreamId);**防环**:DFS 从 upstreamId 沿其上游链,若可达 jobId 则成环拒绝;否则 insert(忽略重复 uk)。
  - `removeDependency(jobId, upstreamId)`:delete by uk。

- [ ] **Step 4: SyncJobController 端点**:
  - `GET /{id}/dependencies` → listDependencies
  - `POST /{id}/dependencies?upstreamId=X` → addDependency(成环返回 fail)
  - `DELETE /{id}/dependencies?upstreamId=X` → removeDependency

- [ ] **Step 5:** `mvn -q -Dtest=DepReadyChecker* test` + 全量 `mvn -q test`(scheduler/controller 构造器加参补测试)。
- [ ] **Step 6: Commit** `feat(sync-m4c): DAG依赖门控+依赖CRUD防环`

---

## Task 4: 前端依赖配置 + 优先级

**Files:** Modify `workspace.html`

- [ ] **Step 1:** 高级选项加优先级输入(priority,默认5),保存/回填/重置(沿用 M2a 高级区模式)。
- [ ] **Step 2:** 任务详情/编辑加"上游依赖"区块:列出已配上游(GET dependencies)、可选其他任务加为上游(POST)、可移除(DELETE)。成环时 toast 报错。
- [ ] **Step 3:** `mvn -q -Dtest=WorkspaceDbSyncUiTest test`(可加断言)。
- [ ] **Step 4: Commit** `feat(sync-m4c): 前端 任务依赖+优先级`

---

## Self-Review

- **Spec 覆盖**: 任务依赖 DAG(Task1/3/4)、资源隔离优先级(Task1/2/4)。✅
- **类型一致**: PrioritizedTask(int,Long,Runnable)+compareTo、DepReadyChecker.allReady(List,Map)、依赖 CRUD 全程一致。✅
- **回归保护**: priority 默认 5(均等)→优先级池行为≈原 FIFO;无依赖配置→DepReadyChecker 恒 true→调度行为同现状;池大小默认 4 同原。⚠️ 换池要处理超时 Future→改查 runningContexts;@PostConstruct 合并。
- **延后**: 无(M4c 为最后功能里程碑,仅余 M4d SqlDialect 重构)。

---

## 部署(M4c)

1. `mvn -o clean package`。
2. 分块上传 + 跑 `sql/31_sync_dag.sql`。
3. 备份+换jar+restart+验证(健康/CDC续传/job状态/dependency 表存在/priority 列)。auto-rollback。
