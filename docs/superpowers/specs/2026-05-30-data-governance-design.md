# DataNote 数据治理生产化 — 总体设计（方案C 全功能）

> 状态：设计稿，待用户评审
> 范围：方案C 全功能；分 P0→P1→P2 三期，每期可独立运行交付
> 定位护栏：单 JAR、Java 8、Spring Boot 2.7、MyBatis-Plus、MySQL 存元数据、Apache Doris 做数仓、静态前端

## 一、目标

把 DataNote 现有"界面齐全、后端骨架可跑、治理闭环大面积缺失"的半成品数据治理，补成**功能齐全、可直接用于生产**的治理体系。覆盖 DCMM/DAMA 核心域：元数据、数据质量、数据标准、分类分级与安全、数据血缘、资产与生命周期、治理本身（健康分/工单/成熟度）。

## 二、锁定的关键决策（用户已确认）

| # | 决策 | 取值 |
|---|---|---|
| 1 | 实施范围 | **方案C 全功能**，P0→P1→P2 分期 |
| 2 | SQL 解析依赖 | **批准引入 Alibaba Druid SQL Parser**（单一依赖，Java 8 兼容） |
| 3 | 多用户/权限 | **建 RBAC 底座**（dn_user/dn_role…），替换单 admin 内存认证 |
| 4 | 治理覆盖侧 | **MySQL 源 + Doris 数仓 双侧** |
| 5 | 前端形态 | **新建独立 `governance.html` 治理总入口 + 抽公共 JS**，与 workspace.html 并列，旧文件改动最小 |
| 6 | 分类分级体系 | **国家三级 + 金融五级 可配并存**（字典表驱动，模板可切换/自定义）；敏感识别 **纯 Java 正则+字典+采样**，不引 NLP/ML |
| 7 | 生命周期编排 | **自动下发 Doris 原生策略**（storage policy / dynamic partition）做冷热分层与 TTL（前提：Doris 已配对象存储冷后端，归档/销毁仍保留三道护栏） |
| 8 | 健康分模型 | **五维加权（可配）+ DCMM 八大域成熟度自评雷达** |

## 三、设计原则（轻量护栏）

1. **元数据存 MySQL、算子下推 Doris、单 JAR**：质量校验、Profiler、落标稽核、敏感采样等一律以 SQL 下推到目标库执行，**绝不在 JVM 内拉全量数据**。
2. **优先扩列复用现有表**，避免推倒重来；新表只在确无承载体时新增。
3. **外科式小步**：每个里程碑边界清晰、可独立交付、独立运行、独立测试。
4. **新依赖仅 Druid 一个**（含其 SQL Parser，可与未来连接池共用）；不引重型框架。
5. **静态前端**：治理界面打包进 jar 的 static 资源，部署形态不变。

## 四、现状结论（已读码核验）

| 模块 | 现状 | 关键缺口 |
|---|---|---|
| 元数据 + 数据地图 | 部分 | 零自动采集；`getAllTablesSummary` 注释/行数恒空；字段级元数据前端零调用；无术语表/Profiler/热度/版本 |
| 数据质量 | 部分 | **`QualityService.java:73` 硬编码 `jdbc:mysql://` 连不上 Doris**；`schedule_cron` 死字段无调度；无阈值/告警/趋势/评分 |
| 血缘 | 桩 | 实为调度 DAG，单条 FROM/JOIN 正则；字段级零实现；`dn_task_dependency` 与 `dn_sync_job_dependency` 两套割裂；无影响/溯源 |
| 指标 | 部分 | `data_source`/`dimensions`/`calc_formula` 纯文本，与表/字段/主题无结构化关联，无指标血缘 |
| 主题域 | 桩 | 后端 CRUD 全，前端仅当下拉；无管理界面；删除不级联留孤儿 |
| 安全/权限/审计/脱敏 | 桩 | 单 admin 内存认证（密码未配即 permitAll）；无用户表/RBAC/数据级权限；脱敏仅同步写时链路；审计仅同步 6 类且 operator 恒 null |
| 数据标准 | 缺失 | 无表、无界面、无落标引擎 |
| 分类分级/敏感识别 | 缺失 | 无分级模型、无密级标签、无识别引擎 |
| 资产/生命周期/成本 | 缺失 | 无体量/访问采集、无冷热/归档/销毁、无僵尸表识别、无成本 |
| 治理健康分/工单/成熟度 | 缺失 | `viewGovernance` 是无路由可达的孤儿死页；顶部"数据治理"Tab 实跳质量 |
| 前端治理界面 | 部分 | 信息架构混乱；单文件 15703 行逼近上限 |

外加两处已核验死代码：`DataMapService.java:580` `getPartitions` 被 `if (System.currentTimeMillis() >= 0) return` 永久短路。

## 五、目标架构

### 5.1 治理域分层

```
┌──────────────────────────────────────────────────────────────┐
│  前端  governance.html（治理总入口）+ 公共 JS（dn-common.js）   │
│  Tab: 资产目录 | 血缘 | 质量 | 标准 | 分类分级 | 安全 | 健康分   │
└──────────────────────────────────────────────────────────────┘
                              │ REST /api/gov/**
┌──────────────────────────────────────────────────────────────┐
│  应用层（Spring Boot 单体，新增 service/governance 包）          │
│  采集 Crawler · 血缘引擎(Druid解析) · 质量引擎(多方言下推)       │
│  落标稽核 · 敏感识别 · SQL改写脱敏 · 生命周期编排 · 健康分计算    │
│  RBAC + 数据级权限拦截 · 统一审计切面                            │
└──────────────────────────────────────────────────────────────┘
        │ 元数据 CRUD                       │ 算子下推 / DDL 下发
┌──────────────────┐              ┌──────────────────────────────┐
│  MySQL 元数据库    │              │  数据侧：MySQL 源库 + Doris 数仓 │
│  dn_* 治理表       │              │  information_schema / SQL 校验  │
└──────────────────┘              └──────────────────────────────┘
```

### 5.2 治理主数据流（闭环）

`采集元数据` → `识别分级打标` → `落标/质量稽核` → `血缘传播(密级/影响)` → `健康分打分` → `生成治理工单` → `整改复检` → 回到采集。

## 六、统一数据模型

原则：尽量扩列复用。下表区分【扩列】与【新表】。

### 6.1 扩列（复用现有表）

- `dn_table_meta` 增：`db_type`(MYSQL/DORIS)、`size_bytes`、`partition_info`、`security_level`、`subject_id`、`lifecycle_policy_id`、`last_access_at`、`last_collected_at`、`status`(active/archived/deprecated)。（已有 `owner/tags/importance/view_count/row_count` 复用）
- `dn_column_meta` 增：`data_type`、`column_length`、`is_nullable`、`is_pk`、`ordinal`、`security_level`、`sensitive_type`、`data_element_id`(落标关联)、`label_source`(AUTO/MANUAL)、`last_collected_at`。
- `dn_quality_rule` 增：`pass_threshold`(通过率阈值)、`block_downstream`(强/弱规则)、`dimension`(完整性/准确性/一致性/唯一性/及时性/有效性)。（已有 `schedule_cron/severity` 复用）
- `dn_metric` 增：`subject_id`、关联通过新表 `dn_metric_ref` 落结构化指标-资产关系。

### 6.2 新表

| 表 | 用途 | 服务里程碑 |
|---|---|---|
| `dn_meta_collect_log` | 采集任务日志与变更记录 | M2 |
| `dn_lineage_edge` | **统一血缘边**：src/dst(type+id)、level(TABLE/COLUMN)、transform_type、source(MAPPING/SQL/SCHEDULE/MANUAL)、confidence | M3 |
| `dn_glossary_term` | 业务术语表 | M2 |
| `dn_user` `dn_role` `dn_user_role` `dn_role_perm` | RBAC 底座 | M6 |
| `dn_data_perm` | 数据级权限（库/表/行/列 + 操作点） | M6/M9 |
| `dn_data_element` | 数据元（数据标准核心，含 sensitive_type/security_level） | M7 |
| `dn_word_root` | 命名词根（中英） | M7 |
| `dn_code_dict` `dn_code_dict_item` | 参考数据/码表 | M7 |
| `dn_standard_check_run` | 落标稽核结果 | M7 |
| `dn_classification_level` | 分级模型字典（国家三级/金融五级模板，可配） | M8 |
| `dn_sensitive_rule` | 敏感识别规则（正则/字典/校验算法） | M8 |
| `dn_label_audit` | 打标/降级审批留痕 | M8 |
| `dn_masking_policy` | 脱敏策略（标签→脱敏算法） | M9 |
| `dn_lifecycle_policy` | 生命周期策略（冷热/归档/TTL/销毁） | M10 |
| `dn_asset_stat` | 资产采集快照（体量/访问/成本） | M10 |
| `dn_governance_metric` | 治理项规则库（五维打分项，配置表驱动） | M11 |
| `dn_governance_score` | 健康分快照（时序） | M11 |
| `dn_governance_issue` | **治理工单单一事实表**：问题→工单→整改→复检闭环 | M11 |
| `dn_maturity_assessment` | DCMM 八大域成熟度自评 | M11 |
| `dn_audit_log` | 全局审计（只增不改） | M12 |

## 七、模块分解与里程碑

每个里程碑 = 独立 plan + 独立实现 + 独立测试，参照本仓库 `docs/superpowers/plans/` 既有风格。

### P0 — 阻断修复 + 价值地基

- **M0 阻断修复与导航统一**（S）：解除 `getPartitions` 短路（下沉真实 `SHOW PARTITIONS`/分区列识别，非分区表返回空）；修正前端"数据治理"Tab 语义；新建 `governance.html` 空壳 + `dn-common.js` 公共层；`viewGovernance` 死页接入真实路由或下线。
- **M1 质量引擎多方言（接 Doris）**（S）：`QualityService` 按 `dn_datasource.dbType` 选 URL/Driver（Doris 走 MySQL 协议但端口/参数不同），5 种规则 + 自定义 SQL 可下推 Doris；复用现有防注入与 `setReadOnly`。
- **M2 元数据自动采集 Crawler**（M）：复用 `dn_datasource`，定时/手动拉 MySQL+Doris `information_schema`（库/表/字段/类型/注释/行数/体量/分区），增量 upsert 进 `dn_table_meta`/`dn_column_meta`（按 6.1 扩列），写采集日志；挂现有 LocalScheduler；补主题域管理界面。
- **M3 统一血缘模型 + 字段映射血缘**（M）：建 `dn_lineage_edge`，把同步任务 `fieldMapping` JSON 直接转字段级血缘边（准确率 100%、零解析）；把 `dn_task_dependency` + `dn_sync_job_dependency` 收敛到统一血缘读取层；指标-资产关系结构化（`dn_metric_ref`）。

### P1 — 标准 / 安全 / 血缘核心闭环

- **M4 SQL 解析血缘 + 影响/溯源 API**（L）：引入 Druid SQL Parser，对 `dn_script` SQL 做表级血缘（读写区分 INSERT…SELECT/CTAS/CTE/子查询）+ 尽力列级（回查 `dn_column_meta` 解歧义，低置信降级）；递归 CTE 实现上游溯源/下游影响清单；人工补录边（MANUAL 不被刷新覆盖）。
- **M5 质量调度 + 阈值 + 告警 + 趋势评分**（L）：接通 `schedule_cron` 到调度器；阈值容忍（通过率 < X% 才失败）+ 红橙分级 + 强/弱规则（强规则失败阻塞下游）；失败下钻异常样本/execSql 前端入口；复用 `dn_alert_config` 多渠道告警 + 静默收敛；质量评分与趋势（`dn_quality_run` 时序聚合）。
- **M6 多用户 + RBAC 底座**（L）：`dn_user/dn_role/dn_user_role/dn_role_perm`，替换 `inMemoryAuthentication` 为 MySQL 用户体系，会话承载用户身份；对象级权限点（查看/查询/导出/管理）；`createdBy/operator` 全链路写真实用户。
- **M7 数据标准（数据元 + 词根 + 码表 + 落标稽核）**（L）：`dn_data_element` 五类属性 + 敏感/密级建议；`dn_word_root` 命名校验；`dn_code_dict` 码表；落标稽核（物理元数据 vs 标准：命名/类型/长度/值域）出落标率；复用质量比对引擎模式。
- **M8 分类分级 + 敏感识别**（L）：`dn_classification_level` 内置国家三级 + 金融五级模板可切换/自定义；纯 Java 正则+校验（身份证/银行卡/手机/邮箱/统一社会信用代码）+ 列名字典 + 采样扫描，置信度阈值给候选标签 + 人工确认；标签回写元数据，沿血缘传播下游取上游最高密级；级别下限强制校验。

### P2 — 成熟度 / 全闭环增强

- **M9 查询期动态脱敏 + 行列权限**（L）：应用层 SQL 改写（Druid 重写）——按角色+列标签把敏感列包裹脱敏表达式、按用户属性拼接行过滤 WHERE；纯 Java 脱敏算法库（掩码/哈希/替换/区间）；标签驱动（打标即生效）。
- **M10 资产盘点 + 生命周期 + 成本 + 无用表**（L）：`dn_asset_stat` 采体量/最近访问；`dn_lifecycle_policy` 编排器**自动下发 Doris** storage policy / dynamic partition；无用表四要素打分（最近访问 + 体量 + 无下游血缘 + 无任务引用）+ **软删宽限期 + 血缘影响校验 + 审批留痕三道护栏**回收；可配单价的成本估算与排行。
- **M11 治理健康分 + 工单闭环 + 成熟度自评**（L）：`dn_governance_metric` 五维（规范/质量/安全/生命周期/血缘）加权打分 0-100，权重配置表可调；`dn_governance_issue` 打通问题→工单→整改→复检闭环 + 按 owner 路由 + 排行榜；DCMM 八大域成熟度自评问卷 + 雷达图；健康分大屏。
- **M12 全局审计中心**（M）：`dn_audit_log` 扩覆盖登录/数据访问/导出/权限变更/元数据与规则变更/打标降级；只增不改 + 全局检索（时间/类型/操作人）+ 导出；下载导出二次校验 + 可见水印（用户/时间/IP）。

## 八、关键技术方案

- **血缘解析**：Druid `SQLStatementParser` + `SchemaStatVisitor` 抽表级读写关系；列级用 select item → source column 映射，歧义回查 `dn_column_meta`，无法确定降级为表级并标低 confidence。血缘存 MySQL 邻接表，溯源/影响用递归 CTE（MySQL 8 支持）。
- **质量/落标/敏感下推**：全部生成 SQL 在目标库执行，只回传聚合数/采样（≤N 条）。
- **敏感识别**：列名字典命中（强信号）+ 采样值正则/校验位（如身份证 18 位校验、银行卡 Luhn）→ 置信度合成 → 阈值产候选 → 人工确认。
- **动态脱敏/行列权限**：查询入口统一经 Druid 重写，按当前用户角色 × 列安全标签注入脱敏函数、按行策略注入 WHERE。
- **生命周期**：策略翻译为 Doris `CREATE STORAGE POLICY` / `dynamic_partition.*` / `ALTER TABLE` 下发；归档/销毁经审批 + 软删宽限期（默认 30 天，可配）+ 血缘影响校验。
- **健康分**：治理项规则库逐项打分 → 五维加权 → 0-100；每次采集/稽核后刷新快照入 `dn_governance_score`。

## 九、前端架构

- 新建 `src/main/resources/static/governance.html` 作治理总入口，内部分 Tab（资产目录/血缘/质量/标准/分类分级/安全/健康分大屏/工单）。
- 抽 `static/js/dn-common.js`：API 封装、通用表格/弹窗/图组件、鉴权头注入；`workspace.html` 与 `dev.html` 后续可渐进复用，本期不强制改造。
- 血缘图、健康分雷达用轻量 Canvas/SVG 自绘或单一 CDN-free 图库（避免新 npm 依赖，沿用项目零框架风格）。

## 十、死代码/孤儿处置

| 项 | 处置 |
|---|---|
| `getPartitions` 短路 | M0 修为真实分区识别 |
| 前端"数据治理"Tab 实跳质量 | M0 修正语义/指向 governance.html |
| `viewGovernance` 孤儿死页 | M0 接真实路由或下线 |
| `SubjectService` 级联删除死代码 | M2 接线复活（级联/防孤儿） |
| `scheduleCron` 死字段 | M5 接调度器消费 |
| `dn_group`（实为告警分组） | 保留语义，RBAC 用新 `dn_role`，不复用 |

## 十一、测试策略

沿用现有 JUnit + Spring Boot Test 风格。每里程碑：核心引擎单测（SQL 构建/解析/脱敏改写/打分算法纯函数优先）+ Controller 切片测 + 关键 SQL 方言兼容测（MySQL/Doris）。血缘解析需覆盖 CTE/子查询/CTAS/INSERT…SELECT/多 JOIN 用例集。

## 十二、风险与护栏

1. **字段级 SQL 血缘是深水区**：复杂 SQL 列级解析准确率有限 → 明确降级策略 + confidence 标注 + 人工补录优先，不追求 100%。
2. **查询期 SQL 改写**：错改导致越权或查询失败 → 充分单测 + 改写失败时**默认拒绝（fail-closed）**而非放行。
3. **生命周期自动下发**：误删风险 → 销毁强制三道护栏 + dry-run 预览。
4. **前端体量**：独立 governance.html 控制单文件规模，必要时按 Tab 再拆 JS 模块。
5. **Java 8 / Doris 兼容**：Druid 版本选 Java 8 兼容线；Doris 不支持的 SQL 特性（如部分 CTE/窗口）在方言层规避。

## 十三、待用户评审/确认项

1. **里程碑交付顺序**：是否按 M0→M1→…→M12 顺序逐个交付？是否允许在确认本 spec 后，先为 **P0（M0-M3）** 出详细实现 plan 并开工，P1/P2 各自再出 plan？
2. **健康分五维默认权重**：拟定 规范20/质量25/安全25/生命周期15/血缘15，是否认可（可后续在配置表调整）？
3. **销毁宽限期**：默认 30 天是否合适？
4. **Doris 冷存后端**：生命周期自动下发依赖 Doris 已配对象存储冷后端；若当前未配，M10 的冷下沉部分将先做策略记录+到期告警，待后端就绪再开自动下发。请确认 Doris 集群冷存状态。
5. **指标口径**："功能齐全可直接生产"以覆盖 DCMM 三级自评 + 上述闭环为验收线，是否一致？
