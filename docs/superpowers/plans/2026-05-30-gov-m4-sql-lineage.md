# M4 — SQL 解析血缘 + 影响/溯源

> 分支 gov-m4。复用 M3 的 `dn_lineage_edge`（source='SQL'）。Druid 1.2.23 已在 pom。

## 目标
对 `dn_script.content` 的 SQL 做表级血缘（写目标 / 读源），尽力列级映射；提供下游影响、上游溯源查询。

## 组件
1. **SqlLineageParser**（纯函数，核心可单测，`service/`）
   - 输入：`sql`、`defaultDb`；输出 `ParseResult{ writeTable, readTables, columnMappings }`。
   - 用 `com.alibaba.druid.sql.*`（MySQL 方言）解析。
   - 覆盖：`INSERT ... SELECT`、`CREATE TABLE AS SELECT (CTAS)`、子查询、多 JOIN、CTE(WITH)。
   - 默认库补全：表无库名时用 `defaultDb`。
   - 列级 best-effort：SELECT item 能明确归属单一源表/源列时产出映射；`*`、聚合、表达式、歧义则降级（不产该列映射）。CTE 名、子查询别名不算物理源表（从读源中剔除）。
   - 降级：解析异常或无写目标 → 返回空 ParseResult，**绝不抛异常**。

2. **SqlLineageService**（`service/`，`@Service @RequiredArgsConstructor`）
   - `rebuildFromScripts()`：先删 `source='SQL'` 旧边；遍历 `dnScriptMapper.selectList(null)`，解析 content，写表级边（confidence=100）+ 列级边（confidence=80）。唯一键冲突忽略。返回边数。
   - 默认库取 `script.databaseName`。

3. **LineageEdgeService**（复用，加 BFS 纯逻辑 + 公开方法）
   - `impact(db, table, maxDepth)`：下游 BFS over 表级边（src→dst），返回去重节点清单。
   - `trace(db, table, maxDepth)`：上游 BFS（dst→src）。
   - 限深默认 10，防环（visited 集合）。

4. **LineageController**（仅加端点）
   - `POST /api/lineage/parse-scripts` → `{edgeCount}`
   - `GET /api/lineage/impact?db=&table=` → 下游节点清单
   - `GET /api/lineage/trace?db=&table=` → 上游节点清单

5. **前端 `js/gov-lineage.js`**（仅编辑此文件）
   - 重建区加"解析脚本SQL血缘"按钮 → `POST /parse-scripts`。
   - 新增"影响/溯源"输入框 + 查询按钮 → 调 impact/trace，列出清单。

## TDD
- `SqlLineageParserTest`（JUnit5，先红）：INSERT...SELECT、CTAS、子查询、多 JOIN、CTE、解析失败降级、默认库补全、列映射。
- `mvn -Dtest=SqlLineageParserTest test` 通过后 `mvn -DskipTests compile`。

## 约束
- 不改 pom/governance.html/init-all.sql；既有 java 仅改 LineageController + LineageEdgeService（已允许注入复用）。
- 无需新 SQL（复用 33 表）。
