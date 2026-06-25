# round6 功能 gap (wkeg7gu0m, 18)

- [crud/] 业务术语(Glossary) 缺编辑入口 | src/main/resources/static/js/gov-assets.js:646 新增 
- [crud/] 敏感识别规则(Classification Rules) 缺编辑入口 | src/main/resources/static/js/gov-classification.js
- [crud/] 生命周期策略(Lifecycle Policies) 缺编辑入口 | src/main/resources/static/js/gov-assets.js:736 新增 
- [crud/] 数据集(Dataset) 缺编辑入口 | src/main/resources/static/js/gov-consumption.js:47
- [op/small] 黄金记录表缺 bizKey 复制 | mdm.js:619-622, /api/mdm/golden 端点
- [op/med] 码表码值缺批量删除 | mdm-refdata.js:91-139, /api/mdm/refdata 端点
- [op/med] 资产清单缺批量打标签 | gov-assets.js:199-335, /api/governance/assets 端点
- [op/med] 指标列表缺批量启停 | gov-consumption.js:249-322, /api/consumption/metri
- [op/small] 质量规则缺 ruleId 复制 | gov-quality.js:491-562, /api/quality/rule/{id} 端点
- [op/small] 交叉引用表缺 sourceId 复制 | mdm.js:1051-1067, /api/mdm/xref 端点
- [op/small] 主数据属性表缺 attrCode 复制 | mdm.js:216-241, /api/mdm/attribute 端点
- [op/small] 实体列表缺 entityCode 复制 | mdm.js:177-196, /api/mdm/entity 端点
- [nav/med] Home dashboard KPI tiles navigate to view module dashboards | file:D:\data\datanote\src\main\resources\static\js
- [nav/med] Metrics edit link forces navigation to metrics module | file:D:\data\datanote\src\main\resources\static\js
- [friction/small] 数据元创建缺编码格式验证与建议 | src/main/resources/static/js/gov-standard.js:219-2
- [friction/small] 质量规则创建缺库表字段级联选择器，需手输重复填写 | src/main/resources/static/js/gov-quality.js:400-41
- [friction/med] 码表新增项缺批量导入，逐个手填低效 | src/main/resources/static/js/gov-standard.js:474-5
- [friction/small] 命名词根创建缺英文与缩写的格式实时反馈 | src/main/resources/static/js/gov-standard.js:338-3