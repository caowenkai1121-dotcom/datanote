# 数据治理 M1：质量引擎多方言（接 Doris）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 修复 `QualityService.java:73` 硬编码 `jdbc:mysql://` 导致质量校验无法对接 Doris 数仓的阻断缺口，使 5 种规则与自定义 SQL 既能跑源库 MySQL，也能下推 Doris 数仓。

**Architecture:** `executeRule` 按规则目标选连接：`datasourceId` 为 null 或 0 → Doris 数仓（复用 `HiveConfig` 连接池，约定 0=数仓，与 `DataMapService` 一致）；否则 → 源数据源（`DriverManager` 建连）。两路径统一 `setCatalog(databaseName)` 后执行（质量 SQL 用非限定表名，依赖连接当前库），finally 还原 catalog 防池连接泄漏。连接选择与 URL 构建抽成静态纯函数便于单测。前端质量规则表单新增「Doris 数仓」选项并在选中时改用 `/api/metadata/*` 元数据接口。

**Tech Stack:** Java 8 / Spring Boot 2.7；JUnit 5；vanilla JS。

**参照：** `docs/superpowers/specs/2026-05-30-data-governance-design.md` §7 M1。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `src/main/java/com/datanote/service/QualityService.java` | 连接按目标选择 + setCatalog + 静态纯函数 | Modify |
| `src/test/java/com/datanote/service/QualityTargetTest.java` | 单测 `isWarehouseTarget` / `buildSourceJdbcUrl` | Create |
| `src/main/resources/static/workspace.html` | 质量规则表单 Doris 数仓选项 + 元数据接口分支 + 校验放行 0 | Modify |
| `src/test/java/com/datanote/web/QualityDorisUiTest.java` | 断言前端 Doris 数仓接线 | Create |

---

## Task 1：QualityService 连接选择纯函数（TDD）

**Files:**
- Modify: `src/main/java/com/datanote/service/QualityService.java`
- Test: `src/test/java/com/datanote/service/QualityTargetTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/service/QualityTargetTest.java`：

```java
package com.datanote.service;

import com.datanote.model.DnQualityRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityTargetTest {

    private DnQualityRule rule(Long dsId) {
        DnQualityRule r = new DnQualityRule();
        r.setDatasourceId(dsId);
        return r;
    }

    @Test
    void nullOrZeroDatasourceMeansWarehouse() {
        assertTrue(QualityService.isWarehouseTarget(rule(null)), "datasourceId=null 应判为数仓");
        assertTrue(QualityService.isWarehouseTarget(rule(0L)), "datasourceId=0 应判为数仓");
    }

    @Test
    void positiveDatasourceMeansSource() {
        assertFalse(QualityService.isWarehouseTarget(rule(5L)), "datasourceId>0 应判为源库");
    }

    @Test
    void buildsSourceJdbcUrl() {
        String url = QualityService.buildSourceJdbcUrl("10.0.0.9", 3306, "mall");
        assertTrue(url.startsWith("jdbc:mysql://10.0.0.9:3306/mall"), "URL 应含 host/port/db: " + url);
        assertTrue(url.contains("useSSL=false"), "URL 应禁用 SSL");
        assertTrue(url.contains("allowPublicKeyRetrieval=true"), "URL 应允许公钥获取");
    }

    @Test
    void buildSourceUrlEquivalenceAcrossArgs() {
        assertEquals(QualityService.buildSourceJdbcUrl("h", 1, "d"),
                QualityService.buildSourceJdbcUrl("h", 1, "d"));
    }
}
```

- [ ] **Step 2：运行，确认失败**

Run: `mvn -q -Dtest=QualityTargetTest test`
Expected: 编译失败 —— `isWarehouseTarget` / `buildSourceJdbcUrl` 不存在。

- [ ] **Step 3：实现**

在 `QualityService.java`：

(a) 顶部 import 区新增：

```java
import com.datanote.config.HiveConfig;
```

(b) 字段区（`private final ObjectMapper objectMapper;` 之后）新增注入：

```java
    private final HiveConfig hiveConfig;
```

(c) 把 `executeRule` 方法（59-91 行）整体替换为：

```java
    public DnQualityRun executeRule(DnQualityRule rule) {
        DnQualityRun run = new DnQualityRun();
        run.setRuleId(rule.getId());
        run.setStartedAt(LocalDateTime.now());

        long startMs = System.currentTimeMillis();
        try {
            validateIdentifier(rule.getDatabaseName(), "数据库名");
            try (Connection conn = openConnection(rule)) {
                String origCatalog = null;
                try {
                    origCatalog = conn.getCatalog();
                    conn.setCatalog(rule.getDatabaseName());
                } catch (SQLException ignore) {
                    // 个别驱动不支持切库，忽略，依赖 SQL 中的库定位
                }
                try {
                    executeCheck(conn, rule, run);
                } finally {
                    if (origCatalog != null) {
                        try { conn.setCatalog(origCatalog); } catch (SQLException ignore) { /* 还原失败不影响结果 */ }
                    }
                }
            }
        } catch (Exception e) {
            run.setRunStatus("error");
            run.setErrorMsg(e.getMessage());
            log.error("质量检查执行异常 ruleId={}", rule.getId(), e);
        }

        long elapsed = System.currentTimeMillis() - startMs;
        run.setDurationMs(elapsed);
        run.setFinishedAt(LocalDateTime.now());
        qualityRunMapper.insert(run);
        return run;
    }

    /**
     * 按规则目标打开连接：数仓走 Doris 连接池，源库走 DriverManager。
     */
    private Connection openConnection(DnQualityRule rule) throws SQLException {
        if (isWarehouseTarget(rule)) {
            return hiveConfig.getConnection();
        }
        DnDatasource ds = datasourceMapper.selectById(rule.getDatasourceId());
        if (ds == null) {
            throw new BusinessException("数据源不存在: " + rule.getDatasourceId());
        }
        String password = CryptoUtil.decryptSafe(ds.getPassword(), cryptoKey);
        String url = buildSourceJdbcUrl(ds.getHost(), ds.getPort(), rule.getDatabaseName());
        return DriverManager.getConnection(url, ds.getUsername(), password);
    }

    /** datasourceId 为 null 或 0 表示目标是 Doris 数仓（约定与 DataMapService 一致） */
    static boolean isWarehouseTarget(DnQualityRule rule) {
        Long id = rule.getDatasourceId();
        return id == null || id == 0L;
    }

    /** 构建源库 JDBC URL（MySQL 协议，Doris/StarRocks 亦兼容） */
    static String buildSourceJdbcUrl(String host, Integer port, String db) {
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000";
    }
```

- [ ] **Step 4：运行测试，确认通过**

Run: `mvn -q -Dtest=QualityTargetTest test`
Expected: PASS（4 用例）。

- [ ] **Step 5：编译确认**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/datanote/service/QualityService.java src/test/java/com/datanote/service/QualityTargetTest.java
git commit -m "fix(gov-m1): 质量引擎支持Doris数仓(datasourceId=0/null走HiveConfig连接池+切库)"
```

---

## Task 2：前端质量规则支持 Doris 数仓目标

**Files:**
- Modify: `src/main/resources/static/workspace.html`（`loadQualityDatasources` 10853、`loadQualityDatabases` 10868、`loadQualityTables` 10885、`loadQualityColumns` 10903、`saveQualityRule` 10922）
- Test: `src/test/java/com/datanote/web/QualityDorisUiTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/web/QualityDorisUiTest.java`：

```java
package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityDorisUiTest {

    private static String ws() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/static/workspace.html")), StandardCharsets.UTF_8);
    }

    @Test
    void datasourceDropdownOffersDorisWarehouse() throws Exception {
        assertTrue(ws().contains("value=\"0\">Doris 数仓"),
                "质量规则数据源下拉应提供 Doris 数仓(value=0) 选项");
    }

    @Test
    void warehouseUsesMetadataEndpoints() throws Exception {
        String html = ws();
        assertTrue(html.contains("/api/metadata/databases"), "数仓应通过元数据接口取库");
        assertTrue(html.contains("/api/metadata/tables?db="), "数仓应通过元数据接口取表");
        assertTrue(html.contains("/api/metadata/columns?db="), "数仓应通过元数据接口取字段");
    }

    @Test
    void saveValidationAllowsZeroDatasource() throws Exception {
        assertTrue(ws().contains("payload.datasourceId === null"),
                "保存校验应用 ===null 判定而非 falsy，放行 datasourceId=0");
    }
}
```

- [ ] **Step 2：运行，确认失败**

Run: `mvn -q -Dtest=QualityDorisUiTest test`
Expected: FAIL（workspace 尚未改）。

- [ ] **Step 3：加 Doris 数仓选项（loadQualityDatasources）**

把 10860 行：

```javascript
      sel.innerHTML = '<option value="">请选择数据源</option>';
```

替换为：

```javascript
      sel.innerHTML = '<option value="">请选择数据源</option><option value="0">Doris 数仓</option>';
```

- [ ] **Step 4：库/表/字段加载按数仓分支取元数据**

把 `loadQualityDatabases`（10868-10883）整体替换为：

```javascript
function loadQualityDatabases() {
  var dsId = document.getElementById('qrDatasource').value;
  if (dsId === '') return Promise.resolve();
  document.getElementById('qrDatabase').innerHTML = '<option value="">加载中...</option>';
  var url = dsId === '0' ? '/api/metadata/databases' : '/api/datasource/' + dsId + '/databases';
  return fetch(url)
    .then(function(r) { return r.json(); })
    .then(function(res) {
      var sel = document.getElementById('qrDatabase');
      sel.innerHTML = '<option value="">请选择数据库</option>';
      if (res.code === 0 && res.data) {
        res.data.forEach(function(db) {
          sel.innerHTML += '<option value="' + escapeHtml(db) + '">' + escapeHtml(db) + '</option>';
        });
      }
    });
}
```

把 `loadQualityTables`（10885-10901）整体替换为：

```javascript
function loadQualityTables() {
  var dsId = document.getElementById('qrDatasource').value;
  var db = document.getElementById('qrDatabase').value;
  if (dsId === '' || !db) return Promise.resolve();
  document.getElementById('qrTable').innerHTML = '<option value="">加载中...</option>';
  var url = dsId === '0'
    ? '/api/metadata/tables?db=' + encodeURIComponent(db)
    : '/api/datasource/' + dsId + '/tables?db=' + db;
  return fetch(url)
    .then(function(r) { return r.json(); })
    .then(function(res) {
      var sel = document.getElementById('qrTable');
      sel.innerHTML = '<option value="">请选择表</option>';
      if (res.code === 0 && res.data) {
        res.data.forEach(function(t) {
          sel.innerHTML += '<option value="' + escapeHtml(t) + '">' + escapeHtml(t) + '</option>';
        });
      }
    });
}
```

把 `loadQualityColumns`（10903-10920）整体替换为：

```javascript
function loadQualityColumns() {
  var dsId = document.getElementById('qrDatasource').value;
  var db = document.getElementById('qrDatabase').value;
  var table = document.getElementById('qrTable').value;
  if (dsId === '' || !db || !table) return Promise.resolve();
  document.getElementById('qrColumn').innerHTML = '<option value="">加载中...</option>';
  var url = dsId === '0'
    ? '/api/metadata/columns?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table)
    : '/api/datasource/' + dsId + '/columns?db=' + db + '&table=' + table;
  return fetch(url)
    .then(function(r) { return r.json(); })
    .then(function(res) {
      var sel = document.getElementById('qrColumn');
      sel.innerHTML = '<option value="">请选择字段</option>';
      if (res.code === 0 && res.data) {
        res.data.forEach(function(c) {
          sel.innerHTML += '<option value="' + escapeHtml(c.name) + '">' + escapeHtml(c.name) + ' (' + escapeHtml(c.type) + ')</option>';
        });
      }
    });
}
```

- [ ] **Step 5：保存校验放行 datasourceId=0**

把 `saveQualityRule`（10927）行：

```javascript
    datasourceId: parseInt(document.getElementById('qrDatasource').value) || null,
```

替换为：

```javascript
    datasourceId: document.getElementById('qrDatasource').value === '' ? null : parseInt(document.getElementById('qrDatasource').value),
```

把校验（10936）行：

```javascript
  if (!payload.ruleName || !payload.datasourceId || !payload.databaseName || !payload.tableName) {
```

替换为：

```javascript
  if (!payload.ruleName || payload.datasourceId === null || isNaN(payload.datasourceId) || !payload.databaseName || !payload.tableName) {
```

- [ ] **Step 6：运行测试，确认通过 + 回归**

Run: `mvn -q -Dtest=QualityDorisUiTest,WorkspaceDbSyncUiTest,GovernanceShellTest test`
Expected: PASS。

- [ ] **Step 7：提交**

```bash
git add src/main/resources/static/workspace.html src/test/java/com/datanote/web/QualityDorisUiTest.java
git commit -m "feat(gov-m1): 质量规则表单支持Doris数仓目标(元数据接口取库表列+校验放行0)"
```

---

## Task 3：M1 全量回归

- [ ] **Step 1：全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，无回归。

- [ ] **Step 2：手动验证（启动应用后，需活 Doris）**

- 质量规则「新建」→ 数据源选「Doris 数仓」→ 库/表/字段从 Doris 元数据加载。
- 保存一条 Doris 表 null_check 规则 → 执行 → 返回真实通过率（不再报连接错）。
- 源库 MySQL 规则仍正常执行（回归）。

---

## Self-Review 记录

- **Spec 覆盖**：M1「按 dbType 选 URL/Driver 使规则下推 Doris」→ Task1（数仓走 HiveConfig 池 + setCatalog）+ Task2（前端可选数仓目标）。
- **占位符**：无。
- **类型一致**：`isWarehouseTarget(DnQualityRule)`、`buildSourceJdbcUrl(String,Integer,String)` 签名与测试一致；前端 `dsId === '0'` 字符串判定与下拉 `value="0"` 一致；`payload.datasourceId === null` 与测试断言一致。
