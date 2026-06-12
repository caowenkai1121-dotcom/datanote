-- ============================================================
-- 75_project_enhance.sql  项目管理加强(P1工程)
-- ①任务联动: 项目任务可引用平台实体(同步任务/脚本/质量规则/治理工单)
-- ②发布关联资产: 发布单记录本次发布的资产清单
-- ============================================================

ALTER TABLE dn_project_task
  ADD COLUMN ref_type VARCHAR(32) DEFAULT NULL COMMENT '关联实体类型 SYNC_JOB/SCRIPT/QUALITY_RULE/GOV_ISSUE',
  ADD COLUMN ref_id   BIGINT      DEFAULT NULL COMMENT '关联实体ID';

ALTER TABLE dn_project_release
  ADD COLUMN asset_json TEXT DEFAULT NULL COMMENT '本次发布的资产清单 [{assetType,assetId,assetName}]';
