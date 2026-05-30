# 数据治理 M0：阻断修复与治理中心地基 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `getPartitions` 死代码为真实 Doris 分区查询；搭建治理中心独立入口 `governance.html` + 公共 JS `dn-common.js`；把孤儿死页 `viewGovernance` 复活为治理中心启动页并修正"数据治理"Tab 语义。

**Architecture:** 后端 `DataMapService.getPartitions` 改走 Doris `SHOW PARTITIONS FROM`，行解析抽成纯函数 `mapDorisPartitionRow` 便于单测，删除遗留 Hive/HDFS 死代码。前端新增 `static/governance.html`（治理总入口，左侧 7 模块导航 + 占位）与 `static/js/dn-common.js`（`DN.api/get/post/del/toast/h/esc` 公共层）；`workspace.html` 复用既有 `viewGovernance` 卡片网格作启动页，路由表把 `#/governance` 指向该启动页、新增 `#/quality` 路由保证质量仍可达。

**Tech Stack:** Java 8 / Spring Boot 2.7 / MyBatis-Plus；JUnit 5；前端 vanilla JS（零框架）。HTML 用 Java 字符串断言测试（沿用 `WorkspaceDbSyncUiTest` 范式）。

**参照设计：** `docs/superpowers/specs/2026-05-30-data-governance-design.md`（§7 M0、§9 前端架构、§10 死代码处置）。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `src/main/java/com/datanote/service/DataMapService.java` | `getPartitions` 改真实 Doris；新增静态纯函数 `mapDorisPartitionRow`；删除 `getHdfsPartitionSize`/`formatBytes`/`hadoopHome` 死代码 | Modify |
| `src/test/java/com/datanote/service/DataMapPartitionMapperTest.java` | 单测 `mapDorisPartitionRow` 行映射 | Create |
| `src/main/resources/static/js/dn-common.js` | 治理页面公共 JS 层 | Create |
| `src/main/resources/static/governance.html` | 治理中心独立入口（7 模块导航 + 占位 + 链接既有功能） | Create |
| `src/main/resources/static/workspace.html` | 路由表 `#/governance`→启动页、新增 `#/quality`；`viewGovernance` 卡片改真实链接 | Modify |
| `src/test/java/com/datanote/web/GovernanceShellTest.java` | 断言 governance.html / dn-common.js / workspace 路由与卡片接线 | Create |

---

## Task 1：getPartitions 改真实 Doris 分区查询

**Files:**
- Modify: `src/main/java/com/datanote/service/DataMapService.java`（`getPartitions` 方法 577-696 行、`getHdfsPartitionSize` 704-750、`formatBytes` 752-762、`hadoopHome` 字段 698-699）
- Test: `src/test/java/com/datanote/service/DataMapPartitionMapperTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/service/DataMapPartitionMapperTest.java`：

```java
package com.datanote.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataMapPartitionMapperTest {

    @Test
    void mapsDorisShowPartitionsRowToPartitionInfo() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PartitionName", "p20260530");
        raw.put("PartitionKey", "dt");
        raw.put("Range", "[types: [DATE]; keys: [2026-05-30]; ]");
        raw.put("Buckets", 10);
        raw.put("State", "NORMAL");
        raw.put("DataSize", "1.234 GB");
        raw.put("VisibleVersionTime", "2026-05-30 11:22:33");

        Map<String, Object> info = DataMapService.mapDorisPartitionRow(raw);

        assertEquals("p20260530", info.get("partition"));
        assertEquals("dt", info.get("partitionKey"));
        assertTrue(info.get("range").toString().contains("2026-05-30"));
        assertEquals("NORMAL", info.get("state"));
        assertEquals("1.234 GB", info.get("totalSizeDisplay"));
        assertEquals("2026-05-30 11:22:33", info.get("lastModified"));
    }

    @Test
    void toleratesMissingOptionalColumns() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PartitionName", "p1");

        Map<String, Object> info = DataMapService.mapDorisPartitionRow(raw);

        assertEquals("p1", info.get("partition"));
        assertEquals("", info.get("partitionKey"));
        assertEquals("", info.get("totalSizeDisplay"));
    }
}
```

- [ ] **Step 2：运行测试，确认失败**

Run: `mvn -q -Dtest=DataMapPartitionMapperTest test`
Expected: 编译失败 —— `DataMapService.mapDorisPartitionRow` 不存在。

- [ ] **Step 3：实现 —— 替换 getPartitions 主体 + 新增纯函数 + 删除死代码**

在 `DataMapService.java` 中，把 `getPartitions`（577-696 行整段方法体）替换为：

```java
    /**
     * 获取 Doris 表的分区列表。DataNote 建的 ODS 表多为非分区表，
     * Doris 非分区表执行 SHOW PARTITIONS 会报错，此处捕获并返回空列表。
     */
    public List<Map<String, Object>> getPartitions(String db, String table) throws SQLException {
        List<Map<String, Object>> partitions = new ArrayList<Map<String, Object>>();
        String sql = "SHOW PARTITIONS FROM `" + db + "`.`" + table + "`";
        try (Connection conn = hiveConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> raw = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= cols; i++) {
                    raw.put(md.getColumnLabel(i), rs.getObject(i));
                }
                partitions.add(mapDorisPartitionRow(raw));
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            // 非分区表：Doris 提示 "is not a partitioned table" / "unpartitioned table"
            if (msg.contains("not a partitioned") || msg.contains("not partitioned")
                    || msg.contains("unpartitioned")) {
                return partitions;
            }
            throw e;
        }
        return partitions;
    }

    /**
     * 把 Doris SHOW PARTITIONS 的一行（列名→值）映射为前端使用的分区信息。
     * 抽成静态纯函数便于单元测试。
     */
    static Map<String, Object> mapDorisPartitionRow(Map<String, Object> raw) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("partition", str(raw.get("PartitionName")));
        p.put("partitionKey", str(raw.get("PartitionKey")));
        p.put("range", str(raw.get("Range")));
        p.put("buckets", raw.get("Buckets"));
        p.put("state", str(raw.get("State")));
        // Doris 的 DataSize 已是可读字符串（如 "1.234 GB"）
        p.put("totalSizeDisplay", str(raw.get("DataSize")));
        p.put("lastModified", str(raw.get("VisibleVersionTime")));
        return p;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
```

然后删除已随旧实现废弃的死代码：`hadoopHome` 字段（698-699 行的 `@Value` 注解 + 字段声明）、`getHdfsPartitionSize` 方法（704-750 行整段）、`formatBytes` 方法（752-762 行整段）。确认 import 中已有 `java.sql.*`（含 `ResultSetMetaData`）与 `java.util.*`（含 `LinkedHashMap`），无需新增 import。

- [ ] **Step 4：运行测试，确认通过**

Run: `mvn -q -Dtest=DataMapPartitionMapperTest test`
Expected: PASS（2 个用例通过）。

- [ ] **Step 5：编译整体确认无残留引用**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS（确认删除的 `formatBytes`/`getHdfsPartitionSize` 无其他调用方）。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/datanote/service/DataMapService.java src/test/java/com/datanote/service/DataMapPartitionMapperTest.java
git commit -m "fix(gov-m0): getPartitions 改真实Doris SHOW PARTITIONS,删除Hive/HDFS死代码"
```

---

## Task 2：公共 JS 层 dn-common.js

**Files:**
- Create: `src/main/resources/static/js/dn-common.js`
- Test: 见 Task 4 的 `GovernanceShellTest`（统一断言）

- [ ] **Step 1：创建 dn-common.js**

创建 `src/main/resources/static/js/dn-common.js`，完整内容：

```javascript
/* DataNote 治理公共层 —— 被 governance.html 等治理页面复用。零框架依赖。 */
(function (global) {
  'use strict';
  var DN = {};

  /** 统一请求：解析 R<T> 信封（code===0 成功），失败抛 Error */
  DN.api = function (url, options) {
    return fetch(url, options || {}).then(function (resp) {
      return resp.json().catch(function () { return {}; }).then(function (body) {
        if (body && typeof body.code !== 'undefined') {
          if (body.code === 0) return body.data;
          throw new Error(body.msg || ('请求失败(' + body.code + ')'));
        }
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return body;
      });
    });
  };

  DN.get = function (url) { return DN.api(url); };

  DN.post = function (url, data) {
    return DN.api(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: data != null ? JSON.stringify(data) : undefined
    });
  };

  DN.del = function (url) { return DN.api(url, { method: 'DELETE' }); };

  /** 轻量 toast 提示 */
  DN.toast = function (msg, type) {
    var t = document.createElement('div');
    t.className = 'dn-toast dn-toast-' + (type || 'info');
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.classList.add('dn-toast-show'); }, 10);
    setTimeout(function () {
      t.classList.remove('dn-toast-show');
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 300);
    }, 2600);
  };

  /** DOM 简化器：DN.h('div', {class:'x', onclick:fn}, [child|text]) */
  DN.h = function (tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (k === 'class') el.className = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'text') el.textContent = v;
        else if (k.indexOf('on') === 0 && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v);
      });
    }
    if (children != null) {
      if (!Array.isArray(children)) children = [children];
      children.forEach(function (c) {
        if (c == null) return;
        el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      });
    }
    return el;
  };

  /** HTML 转义 */
  DN.esc = function (s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  global.DN = DN;
})(window);
```

- [ ] **Step 2：提交**

```bash
git add src/main/resources/static/js/dn-common.js
git commit -m "feat(gov-m0): 新增治理公共JS层 dn-common.js(DN.api/post/del/toast/h/esc)"
```

---

## Task 3：治理中心入口 governance.html

**Files:**
- Create: `src/main/resources/static/governance.html`
- Test: 见 Task 4 的 `GovernanceShellTest`

- [ ] **Step 1：创建 governance.html**

创建 `src/main/resources/static/governance.html`，完整内容：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DataNote · 数据治理中心</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; color: #1f2329; background: #f5f6f8; }
  .gov-top { height: 52px; background: #fff; border-bottom: 1px solid #e5e6eb; display: flex; align-items: center; padding: 0 20px; gap: 12px; }
  .gov-top .brand { font-weight: 600; font-size: 16px; color: #1f2329; }
  .gov-top .brand .accent { color: #1890ff; }
  .gov-top .spacer { flex: 1; }
  .gov-top a.back { color: #4e5969; text-decoration: none; font-size: 13px; padding: 6px 12px; border: 1px solid #e5e6eb; border-radius: 6px; }
  .gov-top a.back:hover { color: #1890ff; border-color: #1890ff; }
  .gov-layout { display: flex; height: calc(100vh - 52px); }
  .gov-nav { width: 200px; background: #fff; border-right: 1px solid #e5e6eb; padding: 10px 0; overflow-y: auto; }
  .gov-nav-item { display: flex; align-items: center; gap: 8px; padding: 10px 18px; cursor: pointer; color: #4e5969; font-size: 14px; border-left: 3px solid transparent; }
  .gov-nav-item:hover { background: rgba(0,0,0,.03); color: #1890ff; }
  .gov-nav-item.active { background: rgba(24,144,255,.06); color: #1890ff; border-left-color: #1890ff; font-weight: 500; }
  .gov-nav-item .badge { margin-left: auto; font-size: 11px; padding: 1px 6px; border-radius: 8px; }
  .badge-live { background: rgba(82,196,26,.12); color: #52c41a; }
  .badge-plan { background: rgba(140,140,140,.12); color: #8c8c8c; }
  .gov-content { flex: 1; padding: 24px 28px; overflow-y: auto; }
  .gov-h1 { font-size: 18px; font-weight: 600; margin-bottom: 6px; }
  .gov-desc { color: #86909c; font-size: 13px; margin-bottom: 20px; }
  .gov-placeholder { background: #fff; border: 1px dashed #d4d7de; border-radius: 8px; padding: 48px; text-align: center; color: #86909c; }
  .gov-placeholder .ms { display: inline-block; margin-top: 10px; font-size: 12px; color: #1890ff; background: rgba(24,144,255,.08); padding: 3px 10px; border-radius: 10px; }
  .gov-btn { display: inline-block; margin-top: 16px; background: #1890ff; color: #fff; text-decoration: none; padding: 8px 18px; border-radius: 6px; font-size: 13px; }
  .gov-btn:hover { background: #40a3ff; }
  .dn-toast { position: fixed; top: 70px; left: 50%; transform: translateX(-50%) translateY(-10px); background: #1f2329; color: #fff; padding: 8px 16px; border-radius: 6px; font-size: 13px; opacity: 0; transition: all .3s; z-index: 9999; }
  .dn-toast-show { opacity: 1; transform: translateX(-50%) translateY(0); }
</style>
</head>
<body>
<div class="gov-top">
  <span class="brand">DataNote <span class="accent">数据治理中心</span></span>
  <span class="spacer"></span>
  <a class="back" href="workspace.html#/home">返回工作台</a>
</div>
<div class="gov-layout">
  <nav class="gov-nav" id="govNav"></nav>
  <main class="gov-content" id="govContent"></main>
</div>

<script src="js/dn-common.js"></script>
<script>
/* 治理模块注册表：M0 仅搭壳，live 模块链入既有工作台，planned 模块占位标注里程碑 */
var GOV_MODULES = [
  { key: 'assets',         label: '资产目录',   status: 'planned', ms: 'M2 / M10', desc: '元数据自动采集、数据目录、资产盘点与生命周期' },
  { key: 'lineage',        label: '数据血缘',   status: 'planned', ms: 'M3 / M4',  desc: '字段级血缘、SQL 解析、影响分析与溯源' },
  { key: 'quality',        label: '数据质量',   status: 'live',    ms: '',          desc: '质量规则、检查执行与历史',
    link: 'workspace.html#/quality' },
  { key: 'standard',       label: '数据标准',   status: 'planned', ms: 'M7',        desc: '数据元、命名词根、码表与落标稽核' },
  { key: 'classification', label: '分类分级',   status: 'planned', ms: 'M8',        desc: '国家三级/金融五级分级、敏感识别与打标' },
  { key: 'security',       label: '数据安全',   status: 'planned', ms: 'M6/M9/M12', desc: 'RBAC、行列权限、动态脱敏与全局审计' },
  { key: 'health',         label: '治理健康分', status: 'planned', ms: 'M11',       desc: '五维健康分、治理工单与 DCMM 成熟度自评' }
];

function currentKey() {
  var h = (location.hash || '').replace(/^#/, '');
  var found = GOV_MODULES.filter(function (m) { return m.key === h; })[0];
  return found ? h : GOV_MODULES[0].key;
}

function renderNav() {
  var nav = document.getElementById('govNav');
  nav.innerHTML = '';
  var active = currentKey();
  GOV_MODULES.forEach(function (m) {
    var item = DN.h('div', {
      class: 'gov-nav-item' + (m.key === active ? ' active' : ''),
      onclick: function () { location.hash = '#' + m.key; }
    }, [
      DN.h('span', { text: m.label }),
      DN.h('span', { class: 'badge ' + (m.status === 'live' ? 'badge-live' : 'badge-plan'),
                     text: m.status === 'live' ? '已上线' : '规划中' })
    ]);
    nav.appendChild(item);
  });
}

function renderContent() {
  var c = document.getElementById('govContent');
  var m = GOV_MODULES.filter(function (x) { return x.key === currentKey(); })[0];
  c.innerHTML = '';
  c.appendChild(DN.h('div', { class: 'gov-h1', text: m.label }));
  c.appendChild(DN.h('div', { class: 'gov-desc', text: m.desc }));
  if (m.status === 'live' && m.link) {
    var box = DN.h('div', { class: 'gov-placeholder' }, [
      DN.h('div', { text: '该模块已上线，当前托管于工作台。' }),
      DN.h('a', { class: 'gov-btn', href: m.link, text: '前往 ' + m.label })
    ]);
    c.appendChild(box);
  } else {
    c.appendChild(DN.h('div', { class: 'gov-placeholder' }, [
      DN.h('div', { text: m.label + ' 模块正在建设中。' }),
      DN.h('span', { class: 'ms', text: '将于里程碑 ' + m.ms + ' 上线' })
    ]));
  }
}

function route() { renderNav(); renderContent(); }
window.addEventListener('hashchange', route);
route();
</script>
</body>
</html>
```

- [ ] **Step 2：提交**

```bash
git add src/main/resources/static/governance.html
git commit -m "feat(gov-m0): 新增治理中心入口 governance.html(7模块导航+占位+质量链入)"
```

---

## Task 4：workspace.html 路由与启动页接线 + 统一壳测试

**Files:**
- Modify: `src/main/resources/static/workspace.html`（路由表 9763 行；`viewGovernance` 卡片 5137-5201 行）
- Create: `src/test/java/com/datanote/web/GovernanceShellTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/web/GovernanceShellTest.java`：

```java
package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceShellTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    void dnCommonExposesApiHelpers() throws Exception {
        String js = read("src/main/resources/static/js/dn-common.js");
        assertTrue(js.contains("DN.api ="), "公共层应暴露 DN.api");
        assertTrue(js.contains("DN.post ="), "公共层应暴露 DN.post");
        assertTrue(js.contains("body.code === 0"), "DN.api 应解析 R 信封 code===0");
    }

    @Test
    void governanceHtmlHasNavAndLoadsCommon() throws Exception {
        String html = read("src/main/resources/static/governance.html");
        assertTrue(html.contains("js/dn-common.js"), "治理入口应加载 dn-common.js");
        assertTrue(html.contains("GOV_MODULES"), "治理入口应有模块注册表");
        assertTrue(html.contains("数据标准") && html.contains("分类分级") && html.contains("治理健康分"),
                "导航应含规划中的治理模块");
        assertTrue(html.contains("workspace.html#/quality"), "质量模块应链入工作台质量路由");
    }

    @Test
    void workspaceRoutesGovernanceToHubAndKeepsQuality() throws Exception {
        String html = read("src/main/resources/static/workspace.html");
        assertTrue(html.contains("'governance':  { view: 'viewGovernance'"),
                "#/governance 应指向治理启动页 viewGovernance");
        assertTrue(html.contains("'quality':"),
                "应新增 #/quality 路由保证质量页可达");
    }

    @Test
    void governanceHubCardsAreWired() throws Exception {
        String html = read("src/main/resources/static/workspace.html");
        assertTrue(html.contains("href=\"#/quality\""), "数据质量卡片应链到 #/quality");
        assertTrue(html.contains("href=\"governance.html#standard\""), "数据标准卡片应链到 governance.html");
        assertTrue(html.contains("href=\"governance.html#security\""), "安全管理卡片应链到 governance.html");
    }
}
```

- [ ] **Step 2：运行测试，确认失败**

Run: `mvn -q -Dtest=GovernanceShellTest test`
Expected: FAIL —— `workspaceRoutesGovernanceToHubAndKeepsQuality` 与 `governanceHubCardsAreWired` 失败（workspace 尚未改）。

- [ ] **Step 3：改路由表**

在 `workspace.html` 第 9763 行，把：

```javascript
  'governance':  { view: 'viewQuality',             layout: 'editor', init: function() { loadQualityOverview(); } },
```

替换为（指向启动页 + 新增 quality 路由）：

```javascript
  'governance':  { view: 'viewGovernance',          layout: 'page',   init: function() {} },
  'quality':     { view: 'viewQuality',             layout: 'editor', init: function() { loadQualityOverview(); } },
```

- [ ] **Step 4：把 viewGovernance 卡片改成真实链接**

在 `viewGovernance` 卡片网格（5137-5201 行）中，按下表把对应 `<a class="module-card" href="#">` 的 `href` 改为真实目标，并把已上线模块的标签 `<span class="m-tag tag-plan">规划中</span>` 改为 `<span class="m-tag tag-live">已上线</span>`：

- 「数据资产」卡片（5140 行 `<a ... href="#">`）→ `href="governance.html#assets"`（标签保持「规划中」）
- 「数据质量」卡片（5150 行 `<a ... href="#">`）→ `href="#/quality"`，标签改为「已上线」
- 「数据标准」卡片（5160 行 `<a ... href="#">`）→ `href="governance.html#standard"`（保持「规划中」）
- 「血缘分析」卡片（5170 行 `<a ... href="#">`）→ `href="governance.html#lineage"`（保持「规划中」）
- 「数仓规范」卡片（5180 行 `<a ... href="#">`）→ `href="governance.html"`（保持「规划中」）
- 「安全管理」卡片（5190 行 `<a ... href="#">`）→ `href="governance.html#security"`（保持「规划中」）

每张卡片是网格中**第 N 个** `<a class="module-card" href="#">`，按出现顺序（资产/质量/标准/血缘/数仓/安全）逐个精确替换其 `href="#"`。「数据质量」卡片同时把其 `<span class="m-tag tag-plan">规划中</span>` 替换为 `<span class="m-tag tag-live">已上线</span>`。

补充：若 `.m-tag.tag-live` 样式未定义，在 `.m-tag.tag-plan` 样式规则之后就近新增一条：

```css
.m-tag.tag-live { background: rgba(82,196,26,.12); color: #52c41a; }
```

（先用 Grep 在 workspace.html 查 `tag-plan {` 定位现有样式位置，紧随其后插入。）

- [ ] **Step 5：运行壳测试，确认通过**

Run: `mvn -q -Dtest=GovernanceShellTest test`
Expected: PASS（4 个用例全过）。

- [ ] **Step 6：回归既有 HTML 测试**

Run: `mvn -q -Dtest=WorkspaceDbSyncUiTest test`
Expected: PASS（确认未破坏既有 workspace 结构断言）。

- [ ] **Step 7：提交**

```bash
git add src/main/resources/static/workspace.html src/test/java/com/datanote/web/GovernanceShellTest.java
git commit -m "feat(gov-m0): 数据治理Tab指向治理启动页,新增#/quality路由,卡片真实接线"
```

---

## Task 5：M0 全量回归 + 手动验证

- [ ] **Step 1：全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS（全部测试通过，无回归）。

- [ ] **Step 2：手动验证清单（启动应用后）**

- 顶部「数据治理」Tab → 进入治理启动页（6 张卡片），不再直跳质量。
- 「数据质量」卡片 → `#/quality`，质量概览正常加载。
- 「数据标准/血缘分析/安全管理」卡片 → 打开 `governance.html` 对应模块占位页。
- `governance.html` 左侧 7 模块导航可切换；「数据质量」模块显示「前往数据质量」按钮链回工作台。
- 对一张 Doris 分区表调用 `/api/metadata/partitions?db=..&table=..` 返回真实分区；对非分区表返回空数组（不报错）。

- [ ] **Step 3：登记里程碑完成**

在 `docs/superpowers/specs/2026-05-30-data-governance-design.md` 不改动；M0 完成后进入 M1（质量引擎多方言）计划。

---

## Self-Review 记录

- **Spec 覆盖**：M0 三项（getPartitions 死代码、governance.html+公共JS 地基、数据治理 Tab 语义/孤儿页）均有对应 Task（1 / 2+3 / 4）。
- **占位符扫描**：无 TBD/TODO；每个代码步骤均含完整代码或精确替换锚点。
- **类型一致性**：`mapDorisPartitionRow(Map<String,Object>)`、`str(Object)` 签名前后一致；前端 `DN.api/post/del/toast/h/esc` 与测试断言一致；路由字符串 `'governance'`/`'quality'`、卡片 href 与测试断言逐一对应。
