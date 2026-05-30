# M7 数据标准（数据元 + 命名词根 + 码表 + 落标稽核）实现计划

> 分支 gov-m7。全部新建文件，不编辑既有 .java。落标稽核只读 `dn_column_meta`，不改其实体。
> 设计依据：docs/superpowers/specs/2026-05-30-data-governance-design.md §7 M7。

## 一、目标与边界

- 数据标准三件套 CRUD：数据元 `dn_data_element`、命名词根 `dn_word_root`、码表 `dn_code_dict` + `dn_code_dict_item`。
- 落标稽核：遍历已采集的物理元数据 `dn_column_meta`，按两类规则比对，出不合规清单与落标率，结果写 `dn_standard_check_run`。
- 抽**纯函数**承载可测核心逻辑（命名拆词、合规判定、类型比对），单测先行。
- 前端 `gov-standard.js`（governance.html 已引用、已注册 `standard` Tab）：三件套管理 + 执行稽核 + 结果展示。

### 硬约束
- 禁编辑 `pom.xml`、`governance.html`、`sql/init-all.sql`。
- 前端仅新增 `static/js/gov-standard.js`。
- 新 SQL 用 `sql/37_data_standard.sql`，幂等（`CREATE TABLE IF NOT EXISTS`）。
- 不编辑任何既有 .java；落标只读 `dn_column_meta`。

## 二、数据模型（sql/37_data_standard.sql）

- `dn_data_element`(id, element_code 唯一, name_cn, data_type, length, value_domain, sensitive_type, security_level, description, created_at)
- `dn_word_root`(id, word_cn, word_en, abbr, category) —— 预置常用词根（id/no/name/amt/time/code/date/user/order/status 等中英）
- `dn_code_dict`(id, dict_code 唯一, dict_name, description) + `dn_code_dict_item`(id, dict_id, item_key, item_value, sort)
- `dn_standard_check_run`(id, scope, total_count, violation_count, pass_rate DECIMAL(5,2), detail TEXT(JSON 不合规清单), created_at)

## 三、后端（全部新建）

实体（com.datanote.model）：`DnDataElement` `DnWordRoot` `DnCodeDict` `DnCodeDictItem` `DnStandardCheckRun`
Mapper（com.datanote.mapper，BaseMapper）：对应 5 个 + 复用既有 `DnColumnMetaMapper`/`DnTableMetaMapper`（不改）
Service：`StandardService`
Controller：`StandardController`（/api/gov/standard/**）

### 落标稽核纯函数（StandardService 内 static，可单测）
- `splitColumnName(String name) -> List<String>`：按下划线拆词，去空、转小写。
- `isNamingCompliant(String columnName, Set<String> roots) -> boolean`：拆词后每个词都能命中 roots（词根集合，预先小写化，含 word_en 与 abbr）才合规；空列名/无词视为不合规。
- `nonCompliantWords(String columnName, Set<String> roots) -> List<String>`：返回不在词根集合中的词（用于清单提示）。
- `typeMatches(String physicalType, String standardType) -> boolean`：列名等于某数据元 element_code 时比对类型（提取类型主名，如 `varchar(50)` -> `varchar`，忽略大小写与长度）。

### 稽核流程（StandardService.runCheck(scope)）
1. 读词根集合（word_en/abbr 小写）、读数据元 Map（element_code 小写 -> data_type）。
2. 遍历 `dn_column_meta`（scope=all 全量；否则按 datasource/库过滤——本期仅 all，预留 scope 字段）。
3. 逐列判定：命名不合规 -> 记一条；列名命中数据元且类型不符 -> 记一条。
4. 汇总 total/violation/passRate，detail 存 JSON 清单（截断上限，避免超大）。
5. 写 `dn_standard_check_run` 返回。

### 端点（StandardController，/api/gov/standard）
- 数据元：GET `/elements`、POST `/element/save`、DELETE `/element/{id}`
- 词根：GET `/roots`、POST `/root/save`、DELETE `/root/{id}`
- 码表：GET `/dicts`、GET `/dict/{id}`（含 items）、POST `/dict/save`、DELETE `/dict/{id}`；POST `/dict/item/save`、DELETE `/dict/item/{id}`
- 落标稽核：POST `/check/run`（执行）、GET `/check/runs`（历史最近 N）、GET `/check/run/{id}`（详情含 detail）

## 四、前端 gov-standard.js
IIFE 注册 `window.GOV_RENDERERS.standard = function(c){...}`，子页签：数据元 / 词根 / 码表 / 落标稽核。
复用 `DN.get/post/del/h/esc/toast`。稽核页：执行按钮 + 落标率 + 不合规清单表 + 历史。

## 五、TDD 步骤
1. 写 `StandardCheckTest`（纯函数：拆词/合规/不合规词/类型比对）—— 先红。
2. 实现 SQL + 实体 + Mapper + StandardService（纯函数）+ Controller。
3. `mvn -q -Dtest=StandardCheckTest test` 通过；再 `mvn -q -DskipTests compile`。
4. 写前端 gov-standard.js。
5. git commit（feat(gov-m7): 前缀）。

## 六、验收
- 单测通过；整体 compile 通过。
- 端点齐全；前端 standard Tab 可管理三件套并执行稽核出落标率。
