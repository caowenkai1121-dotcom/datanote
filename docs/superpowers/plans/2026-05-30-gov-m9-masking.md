# M9 — 查询期动态脱敏 + 行列权限（实现计划）

> 分支 gov-m9（基于 master 含 P0+P1）。最敏感改动：改 SQL 工作台执行路径。
> 第一原则：**默认/admin/单用户态行为零变化**；改写失败仅对受限用户 fail-closed，admin 永不被拒。

## 一、范围与边界

- 仅对 **SELECT 查询** 做改写；DDL/INSERT/UPDATE/DELETE 等非 SELECT 一律原样放行（脱敏对它们无意义，且改写它们风险极高）。
- 列脱敏：把命中敏感列的 SELECT 项用 SQL 函数包裹（MASK/HASH/REPLACE/RANGE），保留原列别名，前端展示不变、值被脱敏。
- 行权限：按角色 × 库.表 注入 `row_filter` 片段到 WHERE（AND 收紧）。
- `SELECT *`：无法精确定位敏感列 → 不改写并标记 degraded，受限用户 fail-closed 拒绝（保守，避免泄露）。
- 解析失败 → 抛 `MaskRewriteException`，受限用户 fail-closed 拒绝。

## 二、绕过与 fail-closed 判定（HiveDdlController 执行前）

取当前用户 `SecurityContextHolder`：
1. **完全绕过（原 SQL，no-op）**，满足任一：
   - 认证未启用（anonymous / principal == "anonymousUser"）——单用户态，零变化；
   - 当前用户为 admin：authorities 含 `ROLE_ADMIN`，或 RBAC 权限集含 `*`；
   - RBAC 权限集含 `data:unmask`。
2. 否则查 MaskingService 取该 SQL 命中的列脱敏 + 行策略：
   - **无任何命中 → no-op（原 SQL）**；
   - 有命中 → 调 SqlMaskRewriter 改写；
   - **改写抛异常（解析失败 / SELECT * 降级）→ 拒绝执行（fail-closed）**。
3. admin 在第 1 步已绕过，第 2 步异常永不影响 admin。

## 三、数据模型（sql/39_masking.sql，幂等）

- `dn_masking_policy`：脱敏策略。维度二选一：`sensitive_type`（按敏感类型，优先预置）或 `column_name`（按具体列）。`masking_func` ∈ MASK/HASH/REPLACE/RANGE。`enabled`。
- `dn_row_policy`：行级权限。`role_code` + `db_name` + `table_name` + `row_filter`（WHERE 片段）+ `enabled`。
- 预置：手机/邮箱/身份证/银行卡 → MASK（按 sensitive_type）。

## 四、核心纯函数 SqlMaskRewriter（先写单测，先红）

签名：
```
String rewrite(String sql, String defaultDb,
               List<ColumnMask> columnMasks,   // 库.表.列 → maskingFunc
               List<RowFilter> rowFilters)      // 库.表 → whereFragment
```
- 无敏感列且无行过滤 → 原样返回（含非 SELECT、纯查询）。
- 单列脱敏：SELECT 项替换为脱敏表达式。
- 行过滤注入：WHERE 追加 `AND (片段)`，无 WHERE 则新建。
- SELECT * 命中库含敏感列 → 抛 MaskRewriteException（降级 fail-closed）。
- 解析失败 → 抛 MaskRewriteException。
- 多表 JOIN 时按表名/别名定位敏感列与行过滤。

脱敏表达式（Doris/MySQL 通用函数）：
- MASK: `CONCAT(LEFT(`col`,3),'****',RIGHT(`col`,4))`
- HASH: `MD5(`col`)`
- REPLACE: `'***'`
- RANGE: `CONCAT(CAST(FLOOR(`col`/10)*10 AS CHAR),'-',...)`（数值分桶，尽力）

## 五、服务与端点

- `MaskingService`：脱敏/行策略 CRUD + `resolve(sql, defaultDb, username)` 装配当前用户可见性（查 dn_column_meta 拿敏感列 → 匹配 dn_masking_policy；查 dn_row_policy 按用户角色）。
- `MaskingController`（/api/gov/masking）：策略 CRUD。
- HiveDdlController：executeSQL / streamExecute / submitExecute 三个入口执行前插入改写网关。

## 六、前端（gov-security.js 追加，勿删 M6 RBAC UI）

在 security 渲染器内追加「脱敏策略」「行级权限」两个管理小节（列表 + 新建/删除）。

## 七、测试与交付

1. SqlMaskRewriterTest 先红后绿：无敏感列原样 / 单列 MASK / 多列 / 行过滤注入 / SELECT * 降级抛异常 / 解析失败抛异常 / 非 SELECT 原样 / JOIN 定位。
2. `mvn -q -Dtest=SqlMaskRewriterTest test` 通过；再 `mvn -q -DskipTests compile`。
3. git commit（feat(gov-m9): 前缀）。
</content>
</invoke>
