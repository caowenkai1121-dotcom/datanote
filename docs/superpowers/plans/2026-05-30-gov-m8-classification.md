# M8：数据分类分级 + 敏感识别 — 实现 plan

> 分支 gov-m8；技术栈 Java 8 / Spring Boot 2.7 / MyBatis-Plus / MySQL；识别纯 Java 正则+字典+校验位，不引 NLP/ML。
> 设计依据：docs/superpowers/specs/2026-05-30-data-governance-design.md §7 M8。

## 目标

把"分级模型可配（国家三级 + 金融五级）+ 敏感识别（正则/字典/校验位）+ 候选打标 + 人工确认回写"补成闭环：
采集元数据 → 选样本下推数仓 → 纯 Java 识别给候选 → 人工勾选确认 → 回写 dn_column_meta 密级/敏感类型 + 写审计留痕。

## 硬约束（已核对）

- 禁改：pom.xml、governance.html、sql/init-all.sql。
- 既有 .java 仅可改 model/DnColumnMeta.java（加 securityLevel/sensitiveType）。
- 前端仅新增 static/js/gov-classification.js（governance.html 已预包含 `<script src="js/gov-classification.js">` 且 tab key='classification' 已注册）。
- 新 SQL 用 sql/38_classification.sql，幂等：CREATE TABLE IF NOT EXISTS；dn_column_meta 加列用 information_schema 守护（参照 sql/32 写法）。

## 数据模型（sql/38_classification.sql）

1. `dn_classification_level(id, scheme, level_code, level_name, sort)`
   预置 scheme='NATIONAL'（一般/重要/核心）、scheme='FINANCE'（L1-L5）。
2. `dn_sensitive_rule(id, rule_name, match_type[COLUMN_NAME/REGEX/VALIDATOR], pattern, sensitive_type, suggest_level, enabled DEFAULT 1, ...)`
   预置：手机、邮箱、身份证、银行卡、统一社会信用代码 的列名关键词 + 正则 + 校验位。
3. `dn_label_audit(id, table_meta_id, column_name, old_level, new_level, sensitive_type, operator, reason, created_at)`。
4. `dn_column_meta` 守护加列 `security_level VARCHAR(20)`、`sensitive_type VARCHAR(40)`。

## 识别引擎（纯函数核心，重点单测）

`util/SensitiveDetector`（无 Spring 依赖，纯静态方法）：
- 校验位/正则纯函数：`isPhone`、`isEmail`、`isIdCard18`（含末位校验码 ISO 7064 MOD 11-2）、`luhn`（银行卡）、`isUscc`（统一社会信用代码 18 位，GB32100 校验位）。
- `detectByColumnName(columnName, rules)` → 候选（列名关键词命中，强信号）。
- `detectByValue(sampleValues, rules)` → 候选（按 match_type=REGEX/VALIDATOR 命中比例算置信度）。
- `detect(columnName, sampleValues, rules)` → 综合：列名命中 + 取值命中 取最高置信度的候选 `{sensitiveType, suggestLevel, confidence, hitColumnName, hitRate}`。

单测 `SensitiveDetectorTest`（先红）：Luhn 正/反例、身份证 18 位校验码正/反例、手机正则、邮箱正则、列名命中、统一社会信用代码、综合 detect 置信度。

## 服务与接口

- 实体：`DnClassificationLevel`、`DnSensitiveRule`、`DnLabelAudit` + 各 Mapper（`@Mapper extends BaseMapper`）。
- `service/ClassificationService`：
  - `levels(scheme)`：查分级模型。
  - 敏感规则 CRUD：`listRules/saveRule/deleteRule/toggleRule`。
  - `scanTable(db, table)`：连数仓（hiveConfig）取每列样本（LIMIT N），调 SensitiveDetector 给候选列表。
  - `confirm(...)`：回写 dn_column_meta.security_level/sensitive_type（按 db+table+column 定位/或建 meta）+ 写 dn_label_audit。
- `controller/ClassificationController`（`/api/gov/classification`）：
  - `GET /levels?scheme=` ；`GET /rules`、`POST /rules`、`DELETE /rules/{id}`、`POST /rules/{id}/toggle`；
  - `GET /scan?db=&table=`（候选）；`POST /confirm`（人工打标）。

## 前端 static/js/gov-classification.js

IIFE 注册 `window.GOV_RENDERERS.classification = function(c){...DN.*...}`：
选 scheme 看分级模型表 + 敏感规则管理（增/删/启停）+ 输入库表点"识别"出候选表 + 勾选 + 选密级 → 确认打标。

## TDD 与交付步骤

1. 写本 plan（本文件）。
2. 先写 `SensitiveDetectorTest`（红）。
3. 实现 SensitiveDetector → 跑测试转绿。
4. 写 SQL、实体、Mapper、Service、Controller、前端 js + 前端契约测 `GovernanceClassificationUiTest`。
5. `mvn -q -Dtest=SensitiveDetectorTest,GovernanceClassificationUiTest test` 通过；`mvn -q -DskipTests compile` 通过。
6. git commit（feat(gov-m8): 前缀）。

## 集成注意

- dn_column_meta 加列后，DnColumnMeta 实体加字段；既有查询用 MyBatis-Plus 默认列映射不受影响（新增列默认 NULL）。
- 部署需先执行 sql/38_classification.sql（幂等，可重复跑）。
- 采样识别依赖 Doris 连接（hiveConfig），未连时 scan 接口返回失败提示。
