# 数据治理 M3：统一血缘模型 + 字段映射血缘 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 建立统一血缘边表 `dn_lineage_edge`，从同步任务 `tableConfig.fields`（字段映射 JSON）直接生成表级 + 字段级血缘边（准确率 100%、零 SQL 解析），并提供血缘查询 API 与治理中心「数据血缘」模块（重建按钮 + 上下游 + 字段来源）。

**Architecture:** `LineageEdgeService` 复用 `SyncJobService.parseTables` 解析同步任务表配置；纯函数 `buildEdgesForJob` 把一个任务的表/字段映射转成血缘边列表（源 `MAPPING`、置信度 100），可单测。重建时清除旧 `MAPPING` 边、保留 `MANUAL` 边。查询走 MySQL 邻接表直查（上下游一跳 + 字段入边），多跳图可视化留 M4。

**Tech Stack:** Java 8 / Spring Boot 2.7 / MyBatis-Plus / fastjson；JUnit 5；vanilla JS。

**迁移耦合提示：** 部署前先应用 `sql/33_lineage_edge.sql`。

**范围说明：** 总体设计 §7 M3 的「dn_metric_ref 指标-资产关联」「统一 dn_task_dependency/dn_sync_job_dependency」「SQL 解析血缘」均移出本里程碑（后者属 M4 Druid），本里程碑只做字段映射血缘这一 100% 准确、零依赖的高价值部分。

**参照：** `docs/superpowers/specs/2026-05-30-data-governance-design.md` §6.2/§7 M3。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `sql/33_lineage_edge.sql` | dn_lineage_edge 建表（幂等） | Create |
| `src/main/java/com/datanote/model/DnLineageEdge.java` | 血缘边实体 | Create |
| `src/main/java/com/datanote/mapper/DnLineageEdgeMapper.java` | 血缘边 Mapper | Create |
| `src/main/java/com/datanote/service/LineageEdgeService.java` | 血缘构建（纯函数）+ 重建 + 查询 | Create |
| `src/test/java/com/datanote/service/LineageEdgeBuildTest.java` | buildEdgesForJob 纯函数单测 | Create |
| `src/main/java/com/datanote/controller/LineageController.java` | 新增边重建/查询端点 | Modify |
| `src/main/resources/static/governance.html` | 数据血缘模块上线 | Modify |
| `src/test/java/com/datanote/web/GovernanceLineageUiTest.java` | 血缘前端断言 | Create |

---

## Task 1：dn_lineage_edge 表 + 实体 + Mapper

**Files:**
- Create: `sql/33_lineage_edge.sql`, `src/main/java/com/datanote/model/DnLineageEdge.java`, `src/main/java/com/datanote/mapper/DnLineageEdgeMapper.java`

- [ ] **Step 1：建表脚本**

创建 `sql/33_lineage_edge.sql`：

```sql
-- 数据治理 M3：统一血缘边表（表级 + 字段级）
USE datanote;

CREATE TABLE IF NOT EXISTS dn_lineage_edge (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  level_type     VARCHAR(10)  NOT NULL COMMENT 'TABLE / COLUMN',
  src_db         VARCHAR(100) NOT NULL DEFAULT '',
  src_table      VARCHAR(200) NOT NULL DEFAULT '',
  src_column     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '表级边为空串',
  dst_db         VARCHAR(100) NOT NULL DEFAULT '',
  dst_table      VARCHAR(200) NOT NULL DEFAULT '',
  dst_column     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '表级边为空串',
  transform_type VARCHAR(20)  DEFAULT 'DIRECT' COMMENT 'DIRECT/TRANSFORM/MASK',
  source         VARCHAR(16)  NOT NULL DEFAULT 'MAPPING' COMMENT 'MAPPING/SQL/SCHEDULE/MANUAL',
  confidence     INT          DEFAULT 100 COMMENT '置信度 0-100',
  job_id         BIGINT       DEFAULT NULL COMMENT '来源同步任务ID',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_edge (level_type, src_db, src_table, src_column, dst_db, dst_table, dst_column, source),
  INDEX idx_src (src_db, src_table),
  INDEX idx_dst (dst_db, dst_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据血缘边';
```

- [ ] **Step 2：实体**

创建 `src/main/java/com/datanote/model/DnLineageEdge.java`：

```java
package com.datanote.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据血缘边 — 对应 dn_lineage_edge
 */
@Data
@TableName("dn_lineage_edge")
public class DnLineageEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String levelType;     // TABLE / COLUMN
    private String srcDb;
    private String srcTable;
    private String srcColumn;
    private String dstDb;
    private String dstTable;
    private String dstColumn;
    private String transformType; // DIRECT / TRANSFORM / MASK
    private String source;        // MAPPING / SQL / SCHEDULE / MANUAL
    private Integer confidence;
    private Long jobId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3：Mapper**

创建 `src/main/java/com/datanote/mapper/DnLineageEdgeMapper.java`：

```java
package com.datanote.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanote.model.DnLineageEdge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DnLineageEdgeMapper extends BaseMapper<DnLineageEdge> {
}
```

- [ ] **Step 4：编译 + 提交**

Run: `mvn -q -DskipTests compile`（Expected: BUILD SUCCESS）

```bash
git add sql/33_lineage_edge.sql src/main/java/com/datanote/model/DnLineageEdge.java src/main/java/com/datanote/mapper/DnLineageEdgeMapper.java
git commit -m "feat(gov-m3): dn_lineage_edge 统一血缘边表/实体/Mapper"
```

---

## Task 2：LineageEdgeService（buildEdgesForJob 纯函数 TDD + 重建 + 查询）

**Files:**
- Create: `src/main/java/com/datanote/service/LineageEdgeService.java`
- Test: `src/test/java/com/datanote/service/LineageEdgeBuildTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/service/LineageEdgeBuildTest.java`：

```java
package com.datanote.service;

import com.datanote.model.DnLineageEdge;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageEdgeBuildTest {

    private FieldMapping fm(String s, String t, Boolean sync, String transform, String mask) {
        FieldMapping f = new FieldMapping();
        f.setSource(s); f.setTarget(t); f.setSync(sync);
        f.setTransformExpression(transform); f.setMaskingType(mask);
        return f;
    }

    private DnSyncJob job() {
        DnSyncJob j = new DnSyncJob();
        j.setId(7L); j.setSourceDb("mall"); j.setTargetDb("ods");
        return j;
    }

    private TableSyncConfig table(String src, String dst, List<FieldMapping> fields) {
        TableSyncConfig tc = new TableSyncConfig();
        tc.setSourceTable(src); tc.setTargetTable(dst); tc.setFields(fields);
        return tc;
    }

    @Test
    void buildsTableEdgeAndColumnEdges() {
        List<FieldMapping> fields = new ArrayList<>();
        fields.add(fm("id", "order_id", true, null, null));
        fields.add(fm("phone", "phone", true, null, "PHONE"));
        fields.add(fm("name", "cust_name", true, "{\"type\":\"substring\"}", null));
        fields.add(fm("age", "age", false, null, null)); // 不同步，应排除
        List<TableSyncConfig> tables = new ArrayList<>();
        tables.add(table("orders", "ods_orders", fields));

        List<DnLineageEdge> edges = LineageEdgeService.buildEdgesForJob(job(), tables);

        long tableEdges = edges.stream().filter(e -> "TABLE".equals(e.getLevelType())).count();
        long colEdges = edges.stream().filter(e -> "COLUMN".equals(e.getLevelType())).count();
        assertEquals(1, tableEdges, "应有 1 条表级边");
        assertEquals(3, colEdges, "应有 3 条字段级边(age 不同步被排除)");

        DnLineageEdge te = edges.stream().filter(e -> "TABLE".equals(e.getLevelType())).findFirst().get();
        assertEquals("mall", te.getSrcDb());
        assertEquals("orders", te.getSrcTable());
        assertEquals("", te.getSrcColumn());
        assertEquals("ods", te.getDstDb());
        assertEquals("ods_orders", te.getDstTable());
        assertEquals("MAPPING", te.getSource());
        assertEquals(100, te.getConfidence());
        assertEquals(7L, te.getJobId());
    }

    @Test
    void transformTypeReflectsMaskAndTransform() {
        List<FieldMapping> fields = new ArrayList<>();
        fields.add(fm("id", "id", true, null, null));
        fields.add(fm("phone", "phone", true, null, "PHONE"));
        fields.add(fm("name", "name", true, "{\"type\":\"x\"}", null));
        List<DnLineageEdge> edges = LineageEdgeService.buildEdgesForJob(job(),
                java.util.Collections.singletonList(table("t", "ods_t", fields)));

        assertEquals("DIRECT", colEdge(edges, "id").getTransformType());
        assertEquals("MASK", colEdge(edges, "phone").getTransformType());
        assertEquals("TRANSFORM", colEdge(edges, "name").getTransformType());
    }

    @Test
    void nullFieldsYieldsOnlyTableEdge() {
        List<DnLineageEdge> edges = LineageEdgeService.buildEdgesForJob(job(),
                java.util.Collections.singletonList(table("t", "ods_t", null)));
        assertEquals(1, edges.size());
        assertTrue("TABLE".equals(edges.get(0).getLevelType()));
    }

    private DnLineageEdge colEdge(List<DnLineageEdge> edges, String srcCol) {
        return edges.stream().filter(e -> "COLUMN".equals(e.getLevelType()) && srcCol.equals(e.getSrcColumn()))
                .findFirst().orElseThrow(() -> new AssertionError("缺字段边: " + srcCol));
    }
}
```

- [ ] **Step 2：运行，确认失败**

Run: `mvn -q -Dtest=LineageEdgeBuildTest test`
Expected: 编译失败 —— `LineageEdgeService` 不存在。

- [ ] **Step 3：实现服务**

创建 `src/main/java/com/datanote/service/LineageEdgeService.java`：

```java
package com.datanote.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanote.mapper.DnLineageEdgeMapper;
import com.datanote.mapper.DnSyncJobMapper;
import com.datanote.model.DnLineageEdge;
import com.datanote.model.DnSyncJob;
import com.datanote.sync.dto.FieldMapping;
import com.datanote.sync.dto.TableSyncConfig;
import com.datanote.sync.service.SyncJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘边服务 — 从同步任务字段映射构建表级/字段级血缘边，并提供查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageEdgeService {

    private final DnLineageEdgeMapper edgeMapper;
    private final DnSyncJobMapper syncJobMapper;
    private final SyncJobService syncJobService;

    /** 从所有同步任务重建 MAPPING 来源的血缘边（保留 MANUAL 边）。返回重建的边数。 */
    @Transactional(rollbackFor = Exception.class)
    public int rebuildFromSyncJobs() {
        QueryWrapper<DnLineageEdge> del = new QueryWrapper<>();
        del.eq("source", "MAPPING");
        edgeMapper.delete(del);

        List<DnSyncJob> jobs = syncJobMapper.selectList(null);
        int count = 0;
        for (DnSyncJob job : jobs) {
            List<TableSyncConfig> tables;
            try {
                tables = syncJobService.parseTables(job);
            } catch (Exception e) {
                log.warn("解析任务表配置失败 jobId={}: {}", job.getId(), e.getMessage());
                continue;
            }
            for (DnLineageEdge edge : buildEdgesForJob(job, tables)) {
                edge.setCreatedAt(LocalDateTime.now());
                edge.setUpdatedAt(LocalDateTime.now());
                try {
                    edgeMapper.insert(edge);
                    count++;
                } catch (Exception e) {
                    // 唯一键冲突（同一边多任务产生）忽略
                }
            }
        }
        return count;
    }

    /** 表级上下游邻居：{upstream:[...], downstream:[...]} */
    public Map<String, List<DnLineageEdge>> tableNeighbors(String db, String table) {
        Map<String, List<DnLineageEdge>> result = new HashMap<>();
        QueryWrapper<DnLineageEdge> down = new QueryWrapper<>();
        down.eq("level_type", "TABLE").eq("src_db", db).eq("src_table", table);
        result.put("downstream", edgeMapper.selectList(down));
        QueryWrapper<DnLineageEdge> up = new QueryWrapper<>();
        up.eq("level_type", "TABLE").eq("dst_db", db).eq("dst_table", table);
        result.put("upstream", edgeMapper.selectList(up));
        return result;
    }

    /** 字段入边：目标为该表的字段级边（这些列从哪来） */
    public List<DnLineageEdge> columnEdgesInto(String db, String table) {
        QueryWrapper<DnLineageEdge> qw = new QueryWrapper<>();
        qw.eq("level_type", "COLUMN").eq("dst_db", db).eq("dst_table", table).orderByAsc("dst_column");
        return edgeMapper.selectList(qw);
    }

    // ========== 纯函数（可单测） ==========

    /** 把一个同步任务的表/字段映射转成血缘边（源 MAPPING，置信度 100）。 */
    static List<DnLineageEdge> buildEdgesForJob(DnSyncJob job, List<TableSyncConfig> tables) {
        List<DnLineageEdge> edges = new ArrayList<>();
        if (tables == null) return edges;
        String srcDb = nz(job.getSourceDb());
        String dstDb = nz(job.getTargetDb());
        for (TableSyncConfig tc : tables) {
            if (tc == null || isBlank(tc.getSourceTable()) || isBlank(tc.getTargetTable())) continue;
            edges.add(edge("TABLE", srcDb, tc.getSourceTable(), "", dstDb, tc.getTargetTable(), "",
                    "DIRECT", job.getId()));
            List<FieldMapping> fields = tc.getFields();
            if (fields == null) continue;
            for (FieldMapping fm : fields) {
                if (fm == null || isBlank(fm.getSource()) || isBlank(fm.getTarget())) continue;
                if (Boolean.FALSE.equals(fm.getSync())) continue; // 不同步字段排除
                edges.add(edge("COLUMN", srcDb, tc.getSourceTable(), fm.getSource(),
                        dstDb, tc.getTargetTable(), fm.getTarget(), transformType(fm), job.getId()));
            }
        }
        return edges;
    }

    static String transformType(FieldMapping fm) {
        if (!isBlank(fm.getMaskingType())) return "MASK";
        if (!isBlank(fm.getTransformExpression())) return "TRANSFORM";
        return "DIRECT";
    }

    private static DnLineageEdge edge(String level, String sdb, String stab, String scol,
                                      String ddb, String dtab, String dcol, String transform, Long jobId) {
        DnLineageEdge e = new DnLineageEdge();
        e.setLevelType(level);
        e.setSrcDb(sdb); e.setSrcTable(stab); e.setSrcColumn(scol);
        e.setDstDb(ddb); e.setDstTable(dtab); e.setDstColumn(dcol);
        e.setTransformType(transform);
        e.setSource("MAPPING");
        e.setConfidence(100);
        e.setJobId(jobId);
        return e;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 4：运行测试，确认通过**

Run: `mvn -q -Dtest=LineageEdgeBuildTest test`
Expected: PASS（3 用例）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/datanote/service/LineageEdgeService.java src/test/java/com/datanote/service/LineageEdgeBuildTest.java
git commit -m "feat(gov-m3): LineageEdgeService 字段映射血缘构建(纯函数)+重建+查询"
```

---

## Task 3：血缘边端点

**Files:**
- Modify: `src/main/java/com/datanote/controller/LineageController.java`

- [ ] **Step 1：注入服务 + 新增端点**

在 `LineageController.java`：

(a) import 区新增：

```java
import com.datanote.model.DnLineageEdge;
import com.datanote.service.LineageEdgeService;
```

(b) 字段区（`private final TaskDependencyService taskDependencyService;` 之后）新增：

```java
    private final LineageEdgeService lineageEdgeService;
```

(c) 在类最后一个方法 `listDeps(...)` 之后、类结束 `}` 之前新增：

```java
    @PostMapping("/rebuild-edges")
    @Operation(summary = "从同步任务重建字段级血缘边")
    public R<Map<String, Object>> rebuildEdges() {
        int count = lineageEdgeService.rebuildFromSyncJobs();
        Map<String, Object> result = new HashMap<>();
        result.put("edgeCount", count);
        return R.ok(result);
    }

    @GetMapping("/table-edges")
    @Operation(summary = "查询表级上下游血缘")
    public R<Map<String, List<DnLineageEdge>>> tableEdges(@RequestParam String db, @RequestParam String table) {
        return R.ok(lineageEdgeService.tableNeighbors(db, table));
    }

    @GetMapping("/column-edges")
    @Operation(summary = "查询字段级入边(目标列来源)")
    public R<List<DnLineageEdge>> columnEdges(@RequestParam String db, @RequestParam String table) {
        return R.ok(lineageEdgeService.columnEdgesInto(db, table));
    }
```

- [ ] **Step 2：编译**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS（`Map`/`List`/`R`/`HashMap` 已 import）。

- [ ] **Step 3：提交**

```bash
git add src/main/java/com/datanote/controller/LineageController.java
git commit -m "feat(gov-m3): 血缘边重建/表级上下游/字段入边查询端点"
```

---

## Task 4：governance.html 数据血缘模块

**Files:**
- Modify: `src/main/resources/static/governance.html`
- Test: `src/test/java/com/datanote/web/GovernanceLineageUiTest.java`

- [ ] **Step 1：写失败测试**

创建 `src/test/java/com/datanote/web/GovernanceLineageUiTest.java`：

```java
package com.datanote.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceLineageUiTest {

    private static String gov() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/static/governance.html")), StandardCharsets.UTF_8);
    }

    @Test
    void lineageModuleLiveAndWired() throws Exception {
        String html = gov();
        assertTrue(html.contains("renderLineage"), "血缘模块应有渲染函数");
        assertTrue(html.contains("/api/lineage/rebuild-edges"), "应能重建血缘边");
        assertTrue(html.contains("/api/lineage/table-edges"), "应能查表级上下游");
        assertTrue(html.contains("/api/lineage/column-edges"), "应能查字段入边");
    }
}
```

- [ ] **Step 2：运行，确认失败**

Run: `mvn -q -Dtest=GovernanceLineageUiTest test`
Expected: FAIL。

- [ ] **Step 3：实现血缘模块**

在 `governance.html` 的 `GOV_MODULES` 中把 `lineage` 项 `status: 'planned'` 改为 `status: 'live'`：

```javascript
  { key: 'lineage',        label: '数据血缘',   status: 'live',    ms: 'M3 / M4',  desc: '字段级血缘、SQL 解析、影响分析与溯源' },
```

在 `MODULE_RENDERERS` 中登记血缘渲染器：

```javascript
var MODULE_RENDERERS = {
  assets: renderAssets,
  lineage: renderLineage
};
```

在 `renderAssets` 函数之后新增：

```javascript
function renderLineage(c) {
  var bar = DN.h('div', { class: 'gov-desc' });
  bar.appendChild(DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '从同步任务重建血缘',
    onclick: function () {
      DN.post('/api/lineage/rebuild-edges').then(function (r) {
        DN.toast('已重建 ' + (r && r.edgeCount != null ? r.edgeCount : 0) + ' 条血缘边');
      }).catch(function (e) { DN.toast(e.message, 'error'); });
    } }));
  c.appendChild(bar);

  var q = DN.h('div', { class: 'gov-desc' });
  var inDb = DN.h('input', { id: 'lnDb', placeholder: '库名(如 ods)',
    style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-right:8px' });
  var inTab = DN.h('input', { id: 'lnTable', placeholder: '表名',
    style: 'padding:6px 10px;border:1px solid #d4d7de;border-radius:6px;margin-right:8px' });
  var btn = DN.h('a', { class: 'gov-btn', href: 'javascript:void(0)', text: '查询血缘',
    onclick: queryLineage, style: 'margin-top:0' });
  q.appendChild(inDb); q.appendChild(inTab); q.appendChild(btn);
  c.appendChild(q);

  c.appendChild(DN.h('div', { id: 'lnResult' }));
}

function queryLineage() {
  var db = document.getElementById('lnDb').value.trim();
  var table = document.getElementById('lnTable').value.trim();
  if (!db || !table) { DN.toast('请输入库名与表名', 'error'); return; }
  var box = document.getElementById('lnResult');
  box.innerHTML = '加载中...';
  var qs = '?db=' + encodeURIComponent(db) + '&table=' + encodeURIComponent(table);
  Promise.all([
    DN.get('/api/lineage/table-edges' + qs),
    DN.get('/api/lineage/column-edges' + qs)
  ]).then(function (res) {
    var nb = res[0] || {}, cols = res[1] || [];
    var up = (nb.upstream || []).map(function (e) { return e.srcDb + '.' + e.srcTable; });
    var down = (nb.downstream || []).map(function (e) { return e.dstDb + '.' + e.dstTable; });
    var html = '<div class="gov-desc"><b>上游表:</b> ' + (up.length ? up.map(DN.esc).join(', ') : '无') + '</div>' +
      '<div class="gov-desc"><b>下游表:</b> ' + (down.length ? down.map(DN.esc).join(', ') : '无') + '</div>';
    if (cols.length) {
      html += '<table style="width:100%;border-collapse:collapse;background:#fff;font-size:13px"><thead>' +
        '<tr style="text-align:left;color:#86909c;border-bottom:1px solid #e5e6eb">' +
        '<th style="padding:8px">目标列</th><th style="padding:8px">来源</th><th style="padding:8px">变换</th></tr></thead><tbody>' +
        cols.map(function (e) {
          return '<tr><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.dstColumn) +
            '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.srcDb + '.' + e.srcTable + '.' + e.srcColumn) +
            '</td><td style="padding:8px;border-bottom:1px solid #f2f3f5">' + DN.esc(e.transformType || '') + '</td></tr>';
        }).join('') + '</tbody></table>';
    } else {
      html += '<div class="gov-placeholder">无字段级血缘（先点上方重建，或该表非同步目标）</div>';
    }
    box.innerHTML = html;
  }).catch(function (e) { box.innerHTML = '<div class="gov-placeholder">查询失败: ' + DN.esc(e.message) + '</div>'; });
}
```

- [ ] **Step 4：运行测试 + 前端回归**

Run: `mvn -q -Dtest=GovernanceLineageUiTest,GovernanceAssetsUiTest,GovernanceShellTest test`
Expected: PASS。

- [ ] **Step 5：提交**

```bash
git add src/main/resources/static/governance.html src/test/java/com/datanote/web/GovernanceLineageUiTest.java
git commit -m "feat(gov-m3): 治理中心数据血缘模块上线(重建+表级上下游+字段入边)"
```

---

## Task 5：M3 全量回归

- [ ] **Step 1：全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，无回归。

- [ ] **Step 2：手动验证（需应用 sql/33 + 有同步任务）**

- governance.html → 数据血缘 → 点「从同步任务重建血缘」→ 提示重建 N 条边。
- 输入目标库表（如 ods / ods_orders）→ 查询 → 显示上下游表 + 字段来源（含 DIRECT/MASK/TRANSFORM）。

---

## Self-Review 记录

- **Spec 覆盖**：M3「dn_lineage_edge + 字段映射血缘」→ Task1（表/实体/Mapper）+ Task2（构建纯函数+重建+查询）+ Task3（端点）+ Task4（UI）。dn_metric_ref / 统一旧依赖表 / SQL 解析血缘 显式移出并记录（后者属 M4）。
- **占位符**：无。
- **类型一致**：`buildEdgesForJob(DnSyncJob, List<TableSyncConfig>)`、`transformType(FieldMapping)` 签名与测试一致；实体字段 `levelType/srcDb/.../transformType/source/confidence/jobId` 在 SQL、实体、服务、前端间一致；端点 `/rebuild-edges`、`/table-edges`、`/column-edges` 与前端/测试一致；`edgeCount` 返回键一致。
