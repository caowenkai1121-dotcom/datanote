# 数据治理 & 主数据 — 全量 UI 梳理(含弹窗/抽屉) + 优化方案

> 方法: 20 个前端文件(gov-*.js × 12 + mdm*.js × 8)逐文件并行精扫, 不留死角地登记每个 抽屉/弹窗/确认框/内联表单/面板/按钮动作/表格, 标注状态(functional/report-only/broken/redundant/missing-validation/orphan/isolated)。再以 DAMA-DMBOK+DCMM 视角综合出 删/改/新 + 联动缺口。全量目录见工作流产物(wf_9ff74532)。

## 一、删除(report-bloat / 冗余 / 死占位)
1. gov-overview: KPI「表数/字段数/库数」三块 onClick 全跳 assets, 落点重复 → **合并为一块「数据资产」**(R123 已做)。
2. gov-standard 总览「近期落标率趋势」折线 = 稽核历史同源数据再画一遍 → 删总览这份, 趋势只在稽核历史。
3. gov-standard「标准建设简评」纯文字无整改入口 → 删或改造为带「去补全」按钮。
4. gov-lineage 孤儿表清单「上游边/下游边」两列恒为 0(孤儿定义即如此) → 删。

## 二、修改(broken / fake / missing-validation / isolated→link)
1. **gov-quality 失败聚焦『立即复跑』通过后不刷新**(列表与真实状态不一致 bug) → 通过后重扫描移除已过规则。**(R123 已做)**
2. **mdm-quality 不合规记录无法进治理工单**(孤岛) → 加「升级工单」POST /api/gov/health/issues, 纳入统一闭环。**(R123 已做)**
3. gov-standard 数据元/词根/码表 **无「编辑」**(只增删, 改靠删重建, 丢关联) → 补行级编辑。
4. gov-security 角色表 **纯只读无增删改**(角色只能 SQL 建, 而分配/行级策略都依赖角色) → 补角色 CRUD。
5. gov-security 脱敏 REPLACE/RANGE: 后端已实现但**固定**(REPLACE→'***'、RANGE→按10分桶, 无参数) → 要么参数化(加 maskingParam 全链)要么 UI 明示固定行为(避免误期)。
6. mdm.js 交叉引用 xrefForm 旧式裸按钮(回车不走校验) → 统一 DN.drawerFoot/formSection。
7. gov-lineage 影响面芯片不可点(看到受影响表却点不动) → 改可点下钻。
8. window.confirm 散落(gov-classification/health/standard/mdm) → 统一 DN.confirm。

## 三、新增(专业缺口)
1. **[P0] 主数据↔数据标准 落标绑定**: MDM 属性建模引用数据元(类型/长度/值域)+词根命名合规校验。标准定义→主数据落标, DAMA 天然闭环, 现在两孤岛。
2. **[P0] 黄金记录变更历史/版本diff/来源追溯**: "单一可信版本"需可证明(谁何时改了什么/合并自哪些源/哪条审批批准)。已有 approval/xref/dedup 可串。
3. [P1] 主数据消费查询 API + 消费分析(下游按实体/主键查黄金记录, 谁在用)。
4. [P1] 匹配规则可配置(模糊/相似度阈值/多字段加权), 现仅精确相等漏真实重复。
5. [P1] MDM 质量结果汇入统一工单(R123 已起步: 升级工单)。
6. [P2] 落标率/主数据合规纳入健康分「规范」维度 + DCMM 自动取数。

## 四、联动缺口(孤岛)
- **MDM 与治理 100% 隔离**: mdm.js 10 处跳转全是域内 mdmGoModule, 零跳治理。黄金记录对应的物理表在治理侧有资产/血缘/密级/质量, MDM 看不到 → 最大孤岛(R123 起步打通: MDM质量→治理工单)。
- 数据标准↔MDM 属性: 标准定了没人引用。
- 两套质量引擎(gov-quality 库表 / mdm-quality 记录)互不感知。
- gov-standard 稽核违规明细/热力不可点, 发现问题到不了整改入口(最后一公里断)。

## 五、R123 已落地(删/改/新各一, 均验证)
- 删: gov-overview 三冗余磁贴→一「数据资产」磁贴。
- 改: gov-quality 立即复跑通过后重扫描(修列表与真实状态不一致)。
- 新+联动: mdm-quality「升级工单」→ 主数据不合规记录生成治理工单(type=MDM, objectRef=mdm:{entity}:{record}), 进入治理工单闭环(分配/流转/关单/SLA)。E2E 验证: 建单 id=10 入治理工单列表。
