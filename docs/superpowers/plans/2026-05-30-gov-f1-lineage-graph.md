# 数据治理 F1：血缘图谱可视化 实施计划

**Goal:** 在现有血缘模块（重建/查询/影响溯源列表）基础上，新增以某表为中心的多跳血缘子图查询 API 与交互式 SVG 有向图可视化。

**Architecture:**
- 后端 `LineageEdgeService` 抽纯函数 `buildSubgraph(edges, startDb, startTable, depth, maxNodes)`：输入表级边四元组列表 + 起点 + 深度 + 节点上限，双向 BFS（同时沿 src→dst 下游、dst→src 上游扩展），返回去重、防环、限节点数的子图 `{nodes, edges}`。可纯单测（不碰数据库）。
- 公共方法 `graph(db, table, depth)`：查全部 TABLE 级边喂给纯函数，返回 `{nodes:[{id,db,table}], edges:[{src,dst,level,source}]}`（id = "db.table"）。
- `LineageController` 新增 `GET /api/lineage/graph?db=&table=&depth=`（depth 默认 2，上限 6；节点上限 100）。
- 前端 `gov-lineage.js` 新增「血缘图谱」交互区：输入库表 + 深度 → 调 graph 端点 → 纯 SVG 自绘分层有向图（上游左 / 中心 / 下游右；节点矩形=表；边=带箭头折线；上下游颜色区分；点节点高亮其相邻边/节点；悬停 tooltip 显示 source/level）。保留现有列表功能。

**Tech Stack:** Java 8 / Spring Boot 2.7 / MyBatis-Plus；JUnit 5；vanilla JS（SVG 自绘，不引图表库）。

**硬约束:** 仅改 `gov-lineage.js`、`LineageEdgeService.java`、`LineageController.java`；可新建测试文件与本计划文档。不碰 pom.xml / workspace.html / governance.html / init-all.sql 及其它 gov-*.js。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `src/main/java/com/datanote/service/LineageEdgeService.java` | 加纯函数 `buildSubgraph` + 公共 `graph` | Modify |
| `src/main/java/com/datanote/controller/LineageController.java` | 加 `GET /api/lineage/graph` | Modify |
| `src/test/java/com/datanote/service/LineageGraphTest.java` | `buildSubgraph` 纯函数单测 | Create |
| `src/main/resources/static/js/gov-lineage.js` | 新增交互式 SVG 血缘图 | Modify |
| `docs/superpowers/plans/2026-05-30-gov-f1-lineage-graph.md` | 本计划 | Create |

## 纯函数契约 `buildSubgraph`

输入：`List<TableEdge>`（src/dst 为 "db.table" 字符串、level、source）、起点 db/table、depth、maxNodes。
输出：`SubGraph{ nodes:Set<String>(含起点), edges:List<TableEdge>(去重) }`。
规则：
- 双向 BFS：每跳同时扩展下游（边 src==当前）与上游（边 dst==当前）。
- visited 防环；节点数达 maxNodes 即停止扩展（起点必含）。
- depth<=0 仅返回起点、无边。
- 自环、重复边去重（按 src|dst|source）。

## 测试用例（先红）
1. 链 A→B→C，起点 B，depth=1 → 节点 {A,B,C}，2 条边。
2. depth=0 → 仅起点、无边。
3. 环 A→B→A，depth=3 → 不死循环，节点 {A,B}，去重后边数有限。
4. maxNodes 截断：星型多下游，maxNodes=3 → 节点数<=3。
5. 起点孤立（无边）→ 仅起点。

## 步骤
- [ ] 写 `LineageGraphTest`（先红）
- [ ] `LineageEdgeService` 实现 `buildSubgraph` + `graph`
- [ ] `LineageController` 加端点
- [ ] `gov-lineage.js` 加 SVG 图
- [ ] `mvn -Dtest=LineageGraphTest test` 通过 + `mvn -DskipTests compile`
- [ ] commit 到 gov-f1
